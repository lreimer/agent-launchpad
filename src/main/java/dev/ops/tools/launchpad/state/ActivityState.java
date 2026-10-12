package dev.ops.tools.launchpad.state;

/**
 * Visual state of a tool execution, task or subagent.
 */
public enum ActivityState {
    /** Currently executing. */
    RUNNING,
    /** Waiting for a decision of the parent / the user (pending approval). */
    WAITING,
    SUCCESS,
    FAILURE,
    DENIED,
    CANCELLED
}
