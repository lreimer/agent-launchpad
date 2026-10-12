package dev.ops.tools.launchpad.state;

/**
 * State of the current agent turn.
 */
public enum TurnState {
    IDLE,
    /** Prompt submitted, agent is working. */
    THINKING,
    /** Turn finished successfully. */
    STOPPED,
    /** Turn ended due to an API error, e.g. a rate limit. */
    FAILED
}
