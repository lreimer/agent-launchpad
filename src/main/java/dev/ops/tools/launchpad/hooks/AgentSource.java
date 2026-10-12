package dev.ops.tools.launchpad.hooks;

/**
 * The agent runtime that emitted a hook. Determines the response format.
 */
public enum AgentSource {
    CLAUDE, COPILOT, GENERIC
}
