package dev.ops.tools.launchpad.hooks;

/**
 * Runtime independent result of processing a hook.
 *
 * @param verdict            the permission verdict for blocking hooks
 * @param rememberForSession whether an allow verdict applies to the tool for the rest of the session
 * @param stop               whether the agent should stop processing entirely (emergency stop)
 * @param reason             human readable reason passed back to the agent
 */
public record HookOutcome(Verdict verdict, boolean rememberForSession, boolean stop, String reason) {

    public enum Verdict {
        /** No decision, the agent continues with its normal flow. */
        NONE,
        ALLOW,
        DENY
    }

    public static HookOutcome none() {
        return new HookOutcome(Verdict.NONE, false, false, null);
    }

    public static HookOutcome allow(String reason) {
        return new HookOutcome(Verdict.ALLOW, false, false, reason);
    }

    public static HookOutcome allowForSession(String reason) {
        return new HookOutcome(Verdict.ALLOW, true, false, reason);
    }

    public static HookOutcome deny(String reason) {
        return new HookOutcome(Verdict.DENY, false, false, reason);
    }

    public static HookOutcome emergencyStop(String reason) {
        return new HookOutcome(Verdict.DENY, false, true, reason);
    }
}
