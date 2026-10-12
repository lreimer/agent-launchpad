package dev.ops.tools.launchpad.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

/**
 * Parses hook payloads of Claude Code (snake_case), GitHub Copilot (camelCase or the VS Code compatible
 * snake_case format) and the generic schema used by the Google ADK plugin into {@link HookEvent}s.
 */
public final class HookEventParser {

    private HookEventParser() {
    }

    /**
     * @param source    the agent runtime
     * @param pathEvent hook name taken from the URL, used if the payload does not name the event
     * @param payload   the JSON payload, may be {@code null}
     */
    public static HookEvent parse(AgentSource source, String pathEvent, JsonNode payload) {
        JsonNode json = payload == null ? NullNode.getInstance() : payload;

        String eventName = text(json, "hook_event_name", "hookEventName", "event");
        HookType type = HookType.fromName(eventName != null ? eventName : pathEvent);
        if (type == HookType.POST_TOOL_USE && isFailure(json)) {
            type = HookType.POST_TOOL_USE_FAILURE;
        }

        HookEvent event = HookEvent.of(source, type, text(json, "session_id", "sessionId"))
                .withAgent(text(json, "agent_id", "agentId"),
                        text(json, "agent_type", "agentType", "agent_name", "agentName"))
                .withTool(text(json, "tool_name", "toolName"), text(json, "tool_use_id", "toolUseId"))
                .withTask(text(json, "task_id", "taskId"))
                .withDetail(detail(type, json))
                .withContext(text(json, "transcript_path", "transcriptPath"),
                        number(json, "context_tokens", "contextTokens"));

        if (type == HookType.MESSAGE_DISPLAY) {
            String delta = text(json, "delta");
            Long chars = number(json, "delta_chars", "deltaChars");
            int length = delta != null ? delta.length() : chars != null ? chars.intValue() : 0;
            Long index = number(json, "index");
            event = event.withStream(index != null ? index.intValue() : 0, length, json.path("final").asBoolean(false));
        }
        return event;
    }

    private static boolean isFailure(JsonNode json) {
        for (JsonNode result : new JsonNode[]{json.path("toolResult"), json.path("tool_result")}) {
            if ("failure".equalsIgnoreCase(text(result, "resultType", "result_type"))) {
                return true;
            }
        }
        return false;
    }

    private static String detail(HookType type, JsonNode json) {
        return switch (type) {
            case SESSION_START -> text(json, "source");
            case SESSION_END -> text(json, "reason");
            case PRE_COMPACT, POST_COMPACT -> text(json, "trigger");
            case POST_TOOL_USE_FAILURE, STOP_FAILURE -> error(json);
            case TASK_CREATED, TASK_COMPLETED -> text(json, "task_subject", "taskSubject");
            default -> null;
        };
    }

    private static String error(JsonNode json) {
        JsonNode error = json.path("error");
        if (error.isObject()) {
            return text(error, "message", "name");
        }
        String value = text(json, "error");
        String details = text(json, "error_details", "errorDetails");
        if (value != null && details != null) {
            return value + ": " + details;
        }
        return value != null ? value : details;
    }

    static String text(JsonNode json, String... names) {
        for (String name : names) {
            JsonNode node = json.path(name);
            if (node.isValueNode() && !node.isNull()) {
                String value = node.asText();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    static Long number(JsonNode json, String... names) {
        for (String name : names) {
            JsonNode node = json.path(name);
            if (node.isNumber() || (node.isTextual() && node.asText().matches("\\d+"))) {
                return node.asLong();
            }
        }
        return null;
    }
}
