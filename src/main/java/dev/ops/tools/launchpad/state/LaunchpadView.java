package dev.ops.tools.launchpad.state;

import java.util.List;

/**
 * Immutable snapshot of everything the Launchpad visualizes.
 *
 * @param pendingApprovals    number of blocking hooks waiting for a decision
 * @param turn                state of the current turn of the focused session
 * @param turnAgeMillis       milliseconds since the turn state changed
 * @param contextFraction     context window usage 0..1, {@code null} if unknown
 * @param compacting          whether a context compaction is in progress
 * @param strips              subagent strips (rows 3-6), entries may be {@code null}
 * @param history             task history states, oldest first (max. 8)
 * @param streamPads          number of lit pads in the stream telemetry row
 * @param streaming           whether an assistant message is currently streaming
 * @param emergencyStop       whether the emergency stop is engaged
 * @param compactionRequested whether a manual compaction was requested
 * @param sessionActive       whether there is an active session
 */
public record LaunchpadView(int pendingApprovals, TurnState turn, long turnAgeMillis, Double contextFraction,
                            boolean compacting, List<Strip> strips, List<ActivityState> history, int streamPads,
                            boolean streaming, boolean emergencyStop, boolean compactionRequested,
                            boolean sessionActive) {

    /** A subagent strip. */
    public record Strip(String agentId, String agentType, ActivityState state) {
    }
}
