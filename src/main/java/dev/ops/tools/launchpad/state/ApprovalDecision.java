package dev.ops.tools.launchpad.state;

/**
 * Decisions that resolve a pending approval.
 */
public enum ApprovalDecision {
    /** Pad (1,1): allow this tool call. */
    ALLOW,
    /** Pad (1,2): block this tool call. */
    DENY,
    /** Pad (1,3): allow this tool for the rest of the session. */
    ALLOW_SESSION,
    /** The requesting subagent was cancelled via its side button. */
    CANCEL_SUBAGENT,
    /** Top row button 1 (CC 104): emergency stop. */
    EMERGENCY_STOP,
    /** Nobody pressed a pad in time, fall back to the normal permission flow. */
    TIMEOUT
}
