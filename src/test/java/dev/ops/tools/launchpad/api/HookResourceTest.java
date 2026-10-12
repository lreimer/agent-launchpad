package dev.ops.tools.launchpad.api;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class HookResourceTest {

    private final String session = UUID.randomUUID().toString();

    @AfterEach
    void noPendingApprovalsLeft() {
        given().get("/api/v1/launchpad").then().body("pendingApprovals", hasSize(0));
    }

    @Test
    void nonBlockingHooksReturnEmptyObject() {
        post("/api/v1/hooks/claude", """
                {"session_id":"%s","hook_event_name":"SessionStart","source":"startup"}""".formatted(session))
                .then().statusCode(200).body("$", anEmptyMap());
        post("/api/v1/hooks/claude", """
                {"session_id":"%s","hook_event_name":"UserPromptSubmit","prompt":"hi"}""".formatted(session))
                .then().statusCode(200).body("$", anEmptyMap());

        given().get("/api/v1/launchpad").then()
                .body("grid", hasSize(8))
                .body("side", hasSize(8))
                .body("top", hasSize(8));
    }

    @Test
    void preToolUseWaitsForAllowPad() throws Exception {
        CompletableFuture<Response> request = async("/api/v1/hooks/pre-tool", preToolUse("Bash", "toolu_1", null));
        awaitPending(1);
        assertTrue(!request.isDone());

        given().post("/api/v1/launchpad/pads/1/1").then().statusCode(204);

        Response response = request.get(10, TimeUnit.SECONDS);
        assertEquals(200, response.statusCode());
        assertEquals("PreToolUse", response.path("hookSpecificOutput.hookEventName"));
        assertEquals("allow", response.path("hookSpecificOutput.permissionDecision"));
    }

    @Test
    void preToolUseBlockedByRedPad() throws Exception {
        CompletableFuture<Response> request = async("/api/v1/hooks/pre-tool", preToolUse("Write", "toolu_2", null));
        awaitPending(1);
        given().post("/api/v1/launchpad/pads/1/2").then().statusCode(204);
        assertEquals("deny", request.get(10, TimeUnit.SECONDS).path("hookSpecificOutput.permissionDecision"));
    }

    @Test
    void approveForSessionAutoApprovesFollowingCalls() throws Exception {
        CompletableFuture<Response> request = async("/api/v1/hooks/claude", """
                {"session_id":"%s","hook_event_name":"PermissionRequest","tool_name":"Edit","tool_input":{}}"""
                .formatted(session));
        awaitPending(1);
        given().post("/api/v1/launchpad/pads/1/3").then().statusCode(204);

        Response response = request.get(10, TimeUnit.SECONDS);
        assertEquals("allow", response.path("hookSpecificOutput.decision.behavior"));
        assertEquals("session", response.path("hookSpecificOutput.decision.updatedPermissions[0].destination"));

        post("/api/v1/hooks/pre-tool", preToolUse("Edit", "toolu_3", null))
                .then().statusCode(200)
                .body("hookSpecificOutput.permissionDecision", equalTo("allow"));
    }

    @Test
    void copilotPreToolUseDenied() throws Exception {
        CompletableFuture<Response> request = async("/api/v1/hooks/copilot/preToolUse", """
                {"sessionId":"%s","timestamp":1704614600000,"cwd":"/tmp","toolName":"bash","toolArgs":"{}"}"""
                .formatted(session));
        awaitPending(1);
        given().post("/api/v1/launchpad/pads/1/2").then().statusCode(204);

        Response response = request.get(10, TimeUnit.SECONDS);
        assertEquals("deny", response.path("permissionDecision"));
        assertEquals("Blocked on Launchpad", response.path("permissionDecisionReason"));
    }

    @Test
    void approvalTimesOutToNormalFlow() {
        long start = System.nanoTime();
        post("/api/v1/hooks/pre-tool", preToolUse("Bash", "toolu_4", null))
                .then().statusCode(200).body("$", anEmptyMap());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() >= 4);
    }

    @Test
    void emergencyStopStopsAgents() throws Exception {
        CompletableFuture<Response> request = async("/api/v1/hooks/pre-tool", preToolUse("Bash", "toolu_5", null));
        awaitPending(1);

        given().post("/api/v1/launchpad/top/1").then().statusCode(204);
        try {
            Response response = request.get(10, TimeUnit.SECONDS);
            assertEquals(false, response.path("continue"));
            assertEquals("deny", response.path("hookSpecificOutput.permissionDecision"));

            post("/api/v1/hooks/claude", """
                    {"session_id":"%s","hook_event_name":"Stop"}""".formatted(session))
                    .then().body("continue", equalTo(false));
        } finally {
            given().post("/api/v1/launchpad/top/1").then().statusCode(204);
        }
        post("/api/v1/hooks/claude", """
                {"session_id":"%s","hook_event_name":"Stop"}""".formatted(session))
                .then().body("$", anEmptyMap());
    }

    @Test
    void cancelledSubagentToolCallsAreDenied() {
        post("/api/v1/hooks/claude", """
                {"session_id":"%s","hook_event_name":"SubagentStart","agent_id":"agent-x","agent_type":"Explore"}"""
                .formatted(session)).then().statusCode(200);

        // side button of row 3 cancels the first subagent strip
        given().post("/api/v1/launchpad/side/3").then().statusCode(204);

        post("/api/v1/hooks/pre-tool", preToolUse("Bash", "toolu_6", "agent-x"))
                .then().statusCode(200)
                .body("hookSpecificOutput.permissionDecision", equalTo("deny"))
                .body("hookSpecificOutput.permissionDecisionReason", equalTo("Subagent cancelled on Launchpad"));
    }

    @Test
    void genericAdkEndpoint() {
        post("/api/v1/hooks/adk", """
                {"hook_event_name":"PostToolUse","session_id":"%s","tool_name":"search","context_tokens":1000}"""
                .formatted(session))
                .then().statusCode(200)
                .body("decision", equalTo("none"))
                .body("stop", equalTo(false));
    }

    @Test
    void invalidRequests() {
        post("/api/v1/hooks/claude", "{not json").then().statusCode(400);
        given().post("/api/v1/launchpad/pads/0/9").then().statusCode(400);
    }

    private String preToolUse(String tool, String toolUseId, String agentId) {
        return """
                {"session_id":"%s","hook_event_name":"PreToolUse","tool_name":"%s","tool_use_id":"%s",%s"tool_input":{}}"""
                .formatted(session, tool, toolUseId, agentId == null ? "" : "\"agent_id\":\"" + agentId + "\",");
    }

    private static Response post(String path, String body) {
        return given().contentType(ContentType.JSON).body(body).post(path);
    }

    private static CompletableFuture<Response> async(String path, String body) {
        return CompletableFuture.supplyAsync(() -> post(path, body));
    }

    private static void awaitPending(int count) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            int pending = given().get("/api/v1/launchpad").path("pendingApprovals.size()");
            if (pending == count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Expected " + count + " pending approvals");
    }
}
