package dev.ops.tools.launchpad.hooks;

/**
 * Normalized hook event, independent of the agent runtime.
 *
 * @param source         the emitting agent runtime
 * @param type           the normalized hook type
 * @param sessionId      session identifier, never {@code null}
 * @param agentId        subagent identifier if the hook fired inside or for a subagent
 * @param agentType      subagent type or name, e.g. {@code Explore}
 * @param toolName       tool name for tool related hooks
 * @param toolUseId      tool use identifier to correlate pre and post tool hooks
 * @param taskId         task identifier for task hooks
 * @param detail         additional detail such as an error message or the session start source
 * @param transcriptPath path to the session transcript, used to estimate the context window usage
 * @param contextTokens  explicitly reported context window usage in tokens
 * @param streamIndex    index of a streamed message chunk
 * @param streamChars    number of characters in a streamed message chunk
 * @param streamFinal    whether this is the final chunk of a streamed message
 */
public record HookEvent(AgentSource source, HookType type, String sessionId, String agentId, String agentType,
                        String toolName, String toolUseId, String taskId, String detail, String transcriptPath,
                        Long contextTokens, int streamIndex, int streamChars, boolean streamFinal) {

    public static final String DEFAULT_SESSION = "default";

    public HookEvent {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = DEFAULT_SESSION;
        }
    }

    public static HookEvent of(AgentSource source, HookType type, String sessionId) {
        return new HookEvent(source, type, sessionId, null, null, null, null, null, null, null, null, 0, 0, false);
    }

    public HookEvent withAgent(String id, String agentType) {
        return new HookEvent(source, type, sessionId, id, agentType, toolName, toolUseId, taskId, detail,
                transcriptPath, contextTokens, streamIndex, streamChars, streamFinal);
    }

    public HookEvent withTool(String name, String useId) {
        return new HookEvent(source, type, sessionId, agentId, agentType, name, useId, taskId, detail,
                transcriptPath, contextTokens, streamIndex, streamChars, streamFinal);
    }

    public HookEvent withTask(String id) {
        return new HookEvent(source, type, sessionId, agentId, agentType, toolName, toolUseId, id, detail,
                transcriptPath, contextTokens, streamIndex, streamChars, streamFinal);
    }

    public HookEvent withDetail(String value) {
        return new HookEvent(source, type, sessionId, agentId, agentType, toolName, toolUseId, taskId, value,
                transcriptPath, contextTokens, streamIndex, streamChars, streamFinal);
    }

    public HookEvent withContext(String path, Long tokens) {
        return new HookEvent(source, type, sessionId, agentId, agentType, toolName, toolUseId, taskId, detail,
                path, tokens, streamIndex, streamChars, streamFinal);
    }

    public HookEvent withStream(int index, int chars, boolean last) {
        return new HookEvent(source, type, sessionId, agentId, agentType, toolName, toolUseId, taskId, detail,
                transcriptPath, contextTokens, index, chars, last);
    }
}
