package dev.ops.tools.launchpad.hooks;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * Renders a {@link HookOutcome} into the JSON response format expected by each agent runtime.
 */
public final class HookResponses {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private HookResponses() {
    }

    public static ObjectNode render(HookEvent event, HookOutcome outcome) {
        return switch (event.source()) {
            case CLAUDE -> claude(event, outcome);
            case COPILOT -> copilot(event, outcome);
            case GENERIC -> generic(outcome);
        };
    }

    /**
     * Claude Code hook output, see the Claude Code hooks reference: universal {@code continue}/{@code stopReason}
     * fields plus {@code hookSpecificOutput} for PreToolUse and PermissionRequest.
     */
    static ObjectNode claude(HookEvent event, HookOutcome outcome) {
        ObjectNode root = JSON.objectNode();
        if (outcome.stop()) {
            root.put("continue", false);
            root.put("stopReason", outcome.reason());
        }
        if (outcome.verdict() == HookOutcome.Verdict.NONE) {
            return root;
        }
        boolean allow = outcome.verdict() == HookOutcome.Verdict.ALLOW;
        if (event.type() == HookType.PRE_TOOL_USE) {
            ObjectNode specific = root.putObject("hookSpecificOutput");
            specific.put("hookEventName", "PreToolUse");
            specific.put("permissionDecision", allow ? "allow" : "deny");
            if (outcome.reason() != null) {
                specific.put("permissionDecisionReason", outcome.reason());
            }
        } else if (event.type() == HookType.PERMISSION_REQUEST) {
            ObjectNode specific = root.putObject("hookSpecificOutput");
            specific.put("hookEventName", "PermissionRequest");
            ObjectNode decision = specific.putObject("decision");
            decision.put("behavior", allow ? "allow" : "deny");
            if (allow && outcome.rememberForSession() && event.toolName() != null) {
                ObjectNode update = decision.putArray("updatedPermissions").addObject();
                update.put("type", "addRules");
                update.putArray("rules").addObject().put("toolName", event.toolName());
                update.put("behavior", "allow");
                update.put("destination", "session");
            }
            if (!allow) {
                if (outcome.reason() != null) {
                    decision.put("message", outcome.reason());
                }
                if (outcome.stop()) {
                    decision.put("interrupt", true);
                }
            }
        }
        return root;
    }

    /**
     * GitHub Copilot hook output: {@code permissionDecision} for preToolUse and {@code behavior} for
     * permissionRequest. Other events produce an empty object.
     */
    static ObjectNode copilot(HookEvent event, HookOutcome outcome) {
        ObjectNode root = JSON.objectNode();
        if (outcome.verdict() == HookOutcome.Verdict.NONE) {
            return root;
        }
        boolean allow = outcome.verdict() == HookOutcome.Verdict.ALLOW;
        if (event.type() == HookType.PRE_TOOL_USE) {
            root.put("permissionDecision", allow ? "allow" : "deny");
            if (outcome.reason() != null) {
                root.put("permissionDecisionReason", outcome.reason());
            }
        } else if (event.type() == HookType.PERMISSION_REQUEST) {
            root.put("behavior", allow ? "allow" : "deny");
            if (!allow) {
                if (outcome.reason() != null) {
                    root.put("message", outcome.reason());
                }
                if (outcome.stop()) {
                    root.put("interrupt", true);
                }
            }
        }
        return root;
    }

    /**
     * Generic output used by the Google ADK plugin and other agent frameworks.
     */
    static ObjectNode generic(HookOutcome outcome) {
        ObjectNode root = JSON.objectNode();
        root.put("decision", outcome.verdict().name().toLowerCase(Locale.ROOT));
        root.put("remember", outcome.rememberForSession());
        root.put("stop", outcome.stop());
        if (outcome.reason() != null) {
            root.put("reason", outcome.reason());
        }
        return root;
    }
}
