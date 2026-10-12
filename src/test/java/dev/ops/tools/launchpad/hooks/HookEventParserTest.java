package dev.ops.tools.launchpad.hooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookEventParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void hookTypeNames() {
        assertEquals(HookType.PRE_TOOL_USE, HookType.fromName("PreToolUse"));
        assertEquals(HookType.PRE_TOOL_USE, HookType.fromName("preToolUse"));
        assertEquals(HookType.PRE_TOOL_USE, HookType.fromName("pre-tool"));
        assertEquals(HookType.USER_PROMPT_SUBMIT, HookType.fromName("userPromptSubmitted"));
        assertEquals(HookType.STOP, HookType.fromName("agentStop"));
        assertEquals(HookType.STOP_FAILURE, HookType.fromName("errorOccurred"));
        assertEquals(HookType.POST_TOOL_USE_FAILURE, HookType.fromName("PostToolUseFailure"));
        assertEquals(HookType.OTHER, HookType.fromName("Notification"));
        assertEquals(HookType.OTHER, HookType.fromName(null));
    }

    @Test
    void claudePreToolUse() throws Exception {
        HookEvent event = HookEventParser.parse(AgentSource.CLAUDE, "claude", json("""
                {"session_id":"abc123","transcript_path":"/tmp/t.jsonl","hook_event_name":"PreToolUse",
                 "tool_name":"Bash","tool_input":{"command":"npm test"},"tool_use_id":"toolu_01",
                 "agent_id":"agent-1","agent_type":"Explore"}"""));
        assertEquals(HookType.PRE_TOOL_USE, event.type());
        assertEquals("abc123", event.sessionId());
        assertEquals("Bash", event.toolName());
        assertEquals("toolu_01", event.toolUseId());
        assertEquals("agent-1", event.agentId());
        assertEquals("Explore", event.agentType());
        assertEquals("/tmp/t.jsonl", event.transcriptPath());
    }

    @Test
    void pathAliasIsUsedWithoutEventName() throws Exception {
        HookEvent event = HookEventParser.parse(AgentSource.CLAUDE, "pre-tool", json("{\"tool_name\":\"Edit\"}"));
        assertEquals(HookType.PRE_TOOL_USE, event.type());
        assertEquals(HookEvent.DEFAULT_SESSION, event.sessionId());
        assertEquals(HookType.OTHER, HookEventParser.parse(AgentSource.CLAUDE, "unknown", null).type());
    }

    @Test
    void claudeStopFailureAndMessageDisplay() throws Exception {
        HookEvent failure = HookEventParser.parse(AgentSource.CLAUDE, null, json("""
                {"hook_event_name":"StopFailure","error":"rate_limit","error_details":"429 Too Many Requests"}"""));
        assertEquals(HookType.STOP_FAILURE, failure.type());
        assertEquals("rate_limit: 429 Too Many Requests", failure.detail());

        HookEvent message = HookEventParser.parse(AgentSource.CLAUDE, null, json("""
                {"hook_event_name":"MessageDisplay","index":2,"final":true,"delta":"Here is the plan:\\n"}"""));
        assertEquals(HookType.MESSAGE_DISPLAY, message.type());
        assertEquals(2, message.streamIndex());
        assertEquals(18, message.streamChars());
        assertTrue(message.streamFinal());
    }

    @Test
    void copilotCamelCase() throws Exception {
        HookEvent pre = HookEventParser.parse(AgentSource.COPILOT, "preToolUse", json("""
                {"sessionId":"s1","timestamp":1704614600000,"cwd":"/tmp","toolName":"bash","toolArgs":"{}"}"""));
        assertEquals(HookType.PRE_TOOL_USE, pre.type());
        assertEquals("s1", pre.sessionId());
        assertEquals("bash", pre.toolName());

        HookEvent post = HookEventParser.parse(AgentSource.COPILOT, "postToolUse", json("""
                {"sessionId":"s1","toolName":"bash","toolResult":{"resultType":"failure","textResultForLlm":"boom"}}"""));
        assertEquals(HookType.POST_TOOL_USE_FAILURE, post.type());

        HookEvent error = HookEventParser.parse(AgentSource.COPILOT, "errorOccurred", json("""
                {"sessionId":"s1","error":{"message":"Network timeout","name":"TimeoutError"}}"""));
        assertEquals(HookType.STOP_FAILURE, error.type());
        assertEquals("Network timeout", error.detail());

        HookEvent subagent = HookEventParser.parse(AgentSource.COPILOT, "subagentStart", json("""
                {"sessionId":"s1","agentName":"explore"}"""));
        assertEquals(HookType.SUBAGENT_START, subagent.type());
        assertEquals("explore", subagent.agentType());
    }

    @Test
    void genericContextTokens() throws Exception {
        HookEvent event = HookEventParser.parse(AgentSource.GENERIC, null, json("""
                {"hook_event_name":"MessageDisplay","session_id":"adk-1","context_tokens":12345,"delta_chars":400}"""));
        assertEquals(12345L, event.contextTokens());
        assertEquals(400, event.streamChars());
    }

    @Test
    void claudeResponses() throws Exception {
        HookEvent pre = HookEventParser.parse(AgentSource.CLAUDE, "pre-tool", json("{\"tool_name\":\"Bash\"}"));
        ObjectNode allow = HookResponses.render(pre, HookOutcome.allow("ok"));
        assertEquals("PreToolUse", allow.at("/hookSpecificOutput/hookEventName").asText());
        assertEquals("allow", allow.at("/hookSpecificOutput/permissionDecision").asText());
        assertEquals("ok", allow.at("/hookSpecificOutput/permissionDecisionReason").asText());

        ObjectNode deny = HookResponses.render(pre, HookOutcome.deny("no"));
        assertEquals("deny", deny.at("/hookSpecificOutput/permissionDecision").asText());

        ObjectNode none = HookResponses.render(pre, HookOutcome.none());
        assertTrue(none.isEmpty());

        ObjectNode stop = HookResponses.render(pre, HookOutcome.emergencyStop("stop!"));
        assertFalse(stop.get("continue").asBoolean());
        assertEquals("stop!", stop.get("stopReason").asText());
        assertEquals("deny", stop.at("/hookSpecificOutput/permissionDecision").asText());

        HookEvent permission = HookEventParser.parse(AgentSource.CLAUDE, null,
                json("{\"hook_event_name\":\"PermissionRequest\",\"tool_name\":\"Bash\"}"));
        ObjectNode session = HookResponses.render(permission, HookOutcome.allowForSession("ok"));
        assertEquals("PermissionRequest", session.at("/hookSpecificOutput/hookEventName").asText());
        assertEquals("allow", session.at("/hookSpecificOutput/decision/behavior").asText());
        assertEquals("addRules", session.at("/hookSpecificOutput/decision/updatedPermissions/0/type").asText());
        assertEquals("Bash", session.at("/hookSpecificOutput/decision/updatedPermissions/0/rules/0/toolName").asText());
        assertEquals("session", session.at("/hookSpecificOutput/decision/updatedPermissions/0/destination").asText());

        ObjectNode interrupt = HookResponses.render(permission, HookOutcome.emergencyStop("stop!"));
        assertEquals("deny", interrupt.at("/hookSpecificOutput/decision/behavior").asText());
        assertTrue(interrupt.at("/hookSpecificOutput/decision/interrupt").asBoolean());
    }

    @Test
    void copilotAndGenericResponses() throws Exception {
        HookEvent pre = HookEventParser.parse(AgentSource.COPILOT, "preToolUse", json("{\"toolName\":\"bash\"}"));
        ObjectNode deny = HookResponses.render(pre, HookOutcome.deny("no"));
        assertEquals("deny", deny.get("permissionDecision").asText());
        assertEquals("no", deny.get("permissionDecisionReason").asText());

        HookEvent permission = HookEventParser.parse(AgentSource.COPILOT, "permissionRequest", json("{\"toolName\":\"bash\"}"));
        assertEquals("allow", HookResponses.render(permission, HookOutcome.allow(null)).get("behavior").asText());

        HookEvent generic = HookEventParser.parse(AgentSource.GENERIC, null, json("{\"hook_event_name\":\"PreToolUse\"}"));
        ObjectNode response = HookResponses.render(generic, HookOutcome.allowForSession("ok"));
        assertEquals("allow", response.get("decision").asText());
        assertTrue(response.get("remember").asBoolean());
        assertFalse(response.get("stop").asBoolean());
    }

    private static JsonNode json(String json) throws Exception {
        return MAPPER.readTree(json);
    }
}
