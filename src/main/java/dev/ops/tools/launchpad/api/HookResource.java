package dev.ops.tools.launchpad.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ops.tools.launchpad.hooks.AgentSource;
import dev.ops.tools.launchpad.hooks.HookEvent;
import dev.ops.tools.launchpad.hooks.HookEventParser;
import dev.ops.tools.launchpad.hooks.HookResponses;
import dev.ops.tools.launchpad.state.AgentStateService;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Hook endpoints. Blocking hooks (PreToolUse, PermissionRequest) are long-polled: the HTTP request stays
 * suspended until a pad on the Launchpad is pressed or the approval times out.
 * <ul>
 *     <li>{@code POST /api/v1/hooks/{event}} Claude Code, e.g. {@code /pre-tool} or {@code /claude}. The
 *     {@code hook_event_name} of the payload takes precedence over the path.</li>
 *     <li>{@code POST /api/v1/hooks/copilot/{event}} GitHub Copilot, e.g. {@code /copilot/preToolUse}</li>
 *     <li>{@code POST /api/v1/hooks/adk} Google ADK and other agent frameworks (generic schema)</li>
 * </ul>
 * The body is parsed as JSON regardless of the content type, so plain {@code curl -d @-} works too.
 */
@Path("/api/v1/hooks")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class HookResource {

    @Inject
    AgentStateService state;

    @Inject
    ObjectMapper mapper;

    @POST
    @Path("{event}")
    @Blocking
    public Uni<ObjectNode> claude(@PathParam("event") String event, String body) {
        return handle(AgentSource.CLAUDE, event, body);
    }

    @POST
    @Path("copilot/{event}")
    @Blocking
    public Uni<ObjectNode> copilot(@PathParam("event") String event, String body) {
        return handle(AgentSource.COPILOT, event, body);
    }

    @POST
    @Path("adk")
    @Blocking
    public Uni<ObjectNode> adk(String body) {
        return handle(AgentSource.GENERIC, null, body);
    }

    private Uni<ObjectNode> handle(AgentSource source, String pathEvent, String body) {
        HookEvent event = HookEventParser.parse(source, pathEvent, parse(body));
        return state.handle(event).map(outcome -> HookResponses.render(event, outcome));
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Hook payload is not valid JSON");
        }
    }
}
