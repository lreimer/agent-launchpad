package dev.ops.tools.launchpad.adk;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.CallbackContext;
import com.google.adk.agents.InvocationContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Google ADK plugin that forwards agent lifecycle callbacks to the Agent Launchpad daemon.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * InMemoryRunner runner = new InMemoryRunner(rootAgent, "my-app", List.of(new LaunchpadPlugin()));
 * }</pre>
 *
 * <p>The plugin posts Claude Code style hook payloads ({@code hook_event_name}, {@code session_id},
 * {@code tool_name}, ...) to {@code POST /api/v1/hooks/adk}. Tool calls wait until the approval is
 * resolved on the Launchpad (or the daemon times out) and are denied when the daemon answers with
 * {@code {"decision": "deny"}}.
 */
public class LaunchpadPlugin extends BasePlugin {

  public static final String DEFAULT_URL =
      Optional.ofNullable(System.getenv("AGENT_LAUNCHPAD_URL"))
          .orElse("http://localhost:8080/api/v1/hooks/adk");

  private static final Logger logger = LoggerFactory.getLogger(LaunchpadPlugin.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final URI url;
  private final Duration approvalTimeout;
  private final Duration timeout;
  private final HttpClient client;
  private final Map<String, String> rootAgents = new ConcurrentHashMap<>();
  private final Set<String> startedSessions = ConcurrentHashMap.newKeySet();

  public LaunchpadPlugin() {
    this(DEFAULT_URL);
  }

  public LaunchpadPlugin(String url) {
    this(url, Duration.ofSeconds(300), Duration.ofSeconds(5));
  }

  public LaunchpadPlugin(String url, Duration approvalTimeout, Duration timeout) {
    super("agent_launchpad");
    this.url = URI.create(url);
    this.approvalTimeout = approvalTimeout;
    this.timeout = timeout;
    this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  // ------------------------------------------------------------------ transport

  private Single<Map<String, Object>> post(String event, String sessionId, Object... fields) {
    return post(event, sessionId, timeout, fields);
  }

  /** Posts the event, {@code fields} are key/value pairs, {@code null} values are omitted. */
  private Single<Map<String, Object>> post(
      String event, String sessionId, Duration requestTimeout, Object... fields) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("hook_event_name", event);
    payload.put("session_id", sessionId);
    for (int i = 0; i + 1 < fields.length; i += 2) {
      if (fields[i + 1] != null) {
        payload.put((String) fields[i], fields[i + 1]);
      }
    }
    return Single.defer(
        () -> {
          HttpRequest request =
              HttpRequest.newBuilder(url)
                  .timeout(requestTimeout)
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(payload)))
                  .build();
          return Single.fromCompletionStage(
              client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        })
        .map(LaunchpadPlugin::parse)
        .onErrorReturn(
            e -> {
              // the daemon is optional, never break the agent because of it
              logger.debug("Agent Launchpad not reachable: {}", e.toString());
              return Map.of();
            });
  }

  private static Map<String, Object> parse(HttpResponse<String> response) throws Exception {
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("HTTP " + response.statusCode());
    }
    String body = response.body();
    return body == null || body.isBlank() ? Map.of() : MAPPER.readValue(body, MAP_TYPE);
  }

  // ------------------------------------------------------------------ helpers

  private static String sessionId(InvocationContext context) {
    return Optional.ofNullable(context.session())
        .map(session -> session.id())
        .filter(id -> !id.isEmpty())
        .orElse("default");
  }

  private static String sessionId(CallbackContext context) {
    return sessionId(context.invocationContext());
  }

  private boolean isRoot(CallbackContext context, String agentName) {
    String root = rootAgents.get(context.invocationId());
    return root == null || root.equals(agentName);
  }

  private String agentId(CallbackContext context, String agentName) {
    return isRoot(context, agentName) ? null : context.invocationId() + ":" + agentName;
  }

  private static <T> Maybe<T> empty(Single<?> request) {
    return request.ignoreElement().andThen(Maybe.empty());
  }

  // ------------------------------------------------------------------ session & turn

  @Override
  public Maybe<Content> beforeRunCallback(InvocationContext invocationContext) {
    rootAgents.put(invocationContext.invocationId(), invocationContext.agent().name());
    String sessionId = sessionId(invocationContext);
    if (startedSessions.add(sessionId)) {
      return empty(post("SessionStart", sessionId, "source", "startup"));
    }
    return Maybe.empty();
  }

  @Override
  public Maybe<Content> onUserMessageCallback(
      InvocationContext invocationContext, Content userMessage) {
    return empty(post("UserPromptSubmit", sessionId(invocationContext)));
  }

  @Override
  public Completable afterRunCallback(InvocationContext invocationContext) {
    rootAgents.remove(invocationContext.invocationId());
    return post("Stop", sessionId(invocationContext)).ignoreElement();
  }

  // ------------------------------------------------------------------ subagents

  @Override
  public Maybe<Content> beforeAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    String agentId = agentId(callbackContext, agent.name());
    if (agentId == null) {
      return Maybe.empty();
    }
    return empty(
        post(
            "SubagentStart",
            sessionId(callbackContext),
            "agent_id", agentId,
            "agent_type", agent.name()));
  }

  @Override
  public Maybe<Content> afterAgentCallback(BaseAgent agent, CallbackContext callbackContext) {
    String agentId = agentId(callbackContext, agent.name());
    if (agentId == null) {
      return Maybe.empty();
    }
    return empty(
        post(
            "SubagentStop",
            sessionId(callbackContext),
            "agent_id", agentId,
            "agent_type", agent.name()));
  }

  // ------------------------------------------------------------------ model / streaming

  @Override
  public Maybe<LlmResponse> afterModelCallback(
      CallbackContext callbackContext, LlmResponse llmResponse) {
    int chars =
        llmResponse.content().flatMap(Content::parts).stream()
            .flatMap(parts -> parts.stream())
            .map(Part::text)
            .mapToInt(text -> text.map(String::length).orElse(0))
            .sum();
    Integer contextTokens =
        llmResponse
            .usageMetadata()
            .flatMap(GenerateContentResponseUsageMetadata::promptTokenCount)
            .orElse(null);
    return empty(
        post(
            "MessageDisplay",
            sessionId(callbackContext),
            "agent_id", agentId(callbackContext, callbackContext.agentName()),
            "delta_chars", chars,
            "final", !llmResponse.partial().orElse(false),
            "context_tokens", contextTokens));
  }

  @Override
  public Maybe<LlmResponse> onModelErrorCallback(
      CallbackContext callbackContext, LlmRequest.Builder llmRequest, Throwable error) {
    return empty(
        post(
            "StopFailure",
            sessionId(callbackContext),
            "error", error.getClass().getSimpleName(),
            "error_details", Objects.toString(error.getMessage(), "")));
  }

  // ------------------------------------------------------------------ tools & approvals

  @Override
  public Maybe<Map<String, Object>> beforeToolCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext) {
    return post(
            "PreToolUse",
            sessionId(toolContext),
            approvalTimeout,
            "agent_id", agentId(toolContext, toolContext.agentName()),
            "agent_type", toolContext.agentName(),
            "tool_name", tool.name(),
            "tool_use_id", toolContext.functionCallId().orElse(null),
            "tool_input", toolArgs)
        .flatMapMaybe(
            response -> {
              if ("deny".equals(response.get("decision"))
                  || Boolean.TRUE.equals(response.get("stop"))) {
                Object reason = response.get("reason");
                String message =
                    reason == null || reason.toString().isEmpty()
                        ? "Denied on the Launchpad"
                        : reason.toString();
                return Maybe.just(Map.<String, Object>of("error", message));
              }
              return Maybe.empty();
            });
  }

  @Override
  public Maybe<Map<String, Object>> afterToolCallback(
      BaseTool tool,
      Map<String, Object> toolArgs,
      ToolContext toolContext,
      Map<String, Object> result) {
    return empty(
        post(
            "PostToolUse",
            sessionId(toolContext),
            "agent_id", agentId(toolContext, toolContext.agentName()),
            "tool_name", tool.name(),
            "tool_use_id", toolContext.functionCallId().orElse(null)));
  }

  @Override
  public Maybe<Map<String, Object>> onToolErrorCallback(
      BaseTool tool, Map<String, Object> toolArgs, ToolContext toolContext, Throwable error) {
    return empty(
        post(
            "PostToolUseFailure",
            sessionId(toolContext),
            "agent_id", agentId(toolContext, toolContext.agentName()),
            "tool_name", tool.name(),
            "tool_use_id", toolContext.functionCallId().orElse(null),
            "error", Objects.toString(error.getMessage(), "")));
  }
}
