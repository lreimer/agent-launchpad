package dev.ops.tools.launchpad.control;

import dev.ops.tools.launchpad.midi.LaunchpadColor;
import dev.ops.tools.launchpad.midi.LaunchpadFrame;
import dev.ops.tools.launchpad.midi.Light;
import dev.ops.tools.launchpad.state.ActivityState;
import dev.ops.tools.launchpad.state.LaunchpadView;

import java.util.List;

import static dev.ops.tools.launchpad.midi.LaunchpadColor.AMBER;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.AMBER_FULL;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.AMBER_LOW;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.GREEN;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.GREEN_FULL;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.GREEN_LOW;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.ORANGE;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.RED;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.RED_FULL;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.RED_LOW;
import static dev.ops.tools.launchpad.midi.LaunchpadColor.YELLOW;

/**
 * Maps a {@link LaunchpadView} onto the physical layout of the Launchpad Mini MK2:
 * <ul>
 *     <li>Rows 1-2: approval / interactive decision zone and turn state (top left pad)</li>
 *     <li>Rows 3-6: subagent execution strips</li>
 *     <li>Row 7: task and tool history, row 8: stream telemetry</li>
 *     <li>Right column: context window gauge</li>
 *     <li>Top row: emergency stop (CC 104), session indicator (CC 108) and manual compaction (CC 111)</li>
 * </ul>
 * Rendering is a pure function of the view and an animation tick.
 */
public final class LaunchpadRenderer {

    public static final int FIRST_STRIP_ROW = 2;
    public static final int HISTORY_ROW = 6;
    public static final int STREAM_ROW = 7;

    public static final int TOP_EMERGENCY_STOP = 0;
    public static final int TOP_SESSION = 4;
    public static final int TOP_COMPACT = 7;

    static final long STOP_FLASH_MILLIS = 1000;

    private LaunchpadRenderer() {
    }

    public static LaunchpadFrame render(LaunchpadView view, long tick) {
        LaunchpadFrame frame = new LaunchpadFrame();
        renderApprovalZone(frame, view);
        renderStrips(frame, view.strips(), tick);
        renderHistory(frame, view.history());
        renderStream(frame, view, tick);
        renderGauge(frame, view);
        renderTopRow(frame, view);
        return frame;
    }

    private static void renderApprovalZone(LaunchpadFrame frame, LaunchpadView view) {
        if (view.pendingApprovals() > 0) {
            frame.grid(0, 0, Light.flashing(AMBER_FULL));
            frame.grid(0, 1, Light.of(RED_FULL));
            frame.grid(0, 2, Light.of(YELLOW));
            int queued = Math.min(LaunchpadFrame.SIZE, view.pendingApprovals() - 1);
            for (int col = 0; col < queued; col++) {
                frame.grid(1, col, Light.of(AMBER_LOW));
            }
            return;
        }
        Light turn = switch (view.turn()) {
            case IDLE -> Light.OFF;
            case THINKING -> Light.flashing(AMBER_LOW);
            case STOPPED -> view.turnAgeMillis() < STOP_FLASH_MILLIS ? Light.of(AMBER_FULL) : Light.of(GREEN_FULL);
            case FAILED -> Light.flashing(RED_FULL);
        };
        frame.grid(0, 0, turn);
    }

    private static void renderStrips(LaunchpadFrame frame, List<LaunchpadView.Strip> strips, long tick) {
        for (int i = 0; i < strips.size(); i++) {
            LaunchpadView.Strip strip = strips.get(i);
            if (strip == null) {
                continue;
            }
            int row = FIRST_STRIP_ROW + i;
            for (int col = 0; col < LaunchpadFrame.SIZE; col++) {
                frame.grid(row, col, stripLight(strip.state(), col, tick));
            }
        }
    }

    private static Light stripLight(ActivityState state, int col, long tick) {
        return switch (state) {
            case RUNNING -> {
                int distance = Math.floorMod(tick - col, LaunchpadFrame.SIZE);
                yield switch (distance) {
                    case 0 -> Light.of(GREEN_FULL);
                    case 1 -> Light.of(GREEN);
                    case 2 -> Light.of(GREEN_LOW);
                    default -> Light.OFF;
                };
            }
            case WAITING -> Light.of(YELLOW);
            case SUCCESS -> Light.of(GREEN);
            case FAILURE, DENIED -> Light.of(RED_FULL);
            case CANCELLED -> Light.of(RED);
        };
    }

    private static void renderHistory(LaunchpadFrame frame, List<ActivityState> history) {
        int offset = Math.max(0, history.size() - LaunchpadFrame.SIZE);
        for (int col = 0; col + offset < history.size(); col++) {
            frame.grid(HISTORY_ROW, col, switch (history.get(col + offset)) {
                case RUNNING -> Light.flashing(YELLOW);
                case WAITING -> Light.flashing(AMBER_FULL);
                case SUCCESS -> Light.of(GREEN_FULL);
                case FAILURE -> Light.of(RED_FULL);
                case DENIED -> Light.of(AMBER_FULL);
                case CANCELLED -> Light.of(RED_LOW);
            });
        }
    }

    private static void renderStream(LaunchpadFrame frame, LaunchpadView view, long tick) {
        for (int col = 0; col < view.streamPads(); col++) {
            Light light;
            if (view.streaming()) {
                light = switch (Math.floorMod(tick - col, 4)) {
                    case 0 -> Light.of(YELLOW);
                    case 1 -> Light.of(AMBER);
                    default -> Light.of(AMBER_LOW);
                };
            } else {
                light = Light.of(AMBER_LOW);
            }
            frame.grid(STREAM_ROW, col, light);
        }
    }

    private static void renderGauge(LaunchpadFrame frame, LaunchpadView view) {
        if (view.compacting()) {
            for (int row = 0; row < LaunchpadFrame.SIZE; row++) {
                frame.side(row, Light.flashing(ORANGE));
            }
            return;
        }
        if (view.contextFraction() == null) {
            return;
        }
        double fraction = view.contextFraction();
        int lit = fraction <= 0 ? 0 : (int) Math.min(LaunchpadFrame.SIZE, Math.ceil(fraction * LaunchpadFrame.SIZE));
        for (int level = 0; level < lit; level++) {
            LaunchpadColor color = level < 4 ? GREEN_FULL : level < 6 ? AMBER_FULL : RED_FULL;
            frame.side(LaunchpadFrame.SIZE - 1 - level, Light.of(color));
        }
    }

    private static void renderTopRow(LaunchpadFrame frame, LaunchpadView view) {
        frame.top(TOP_EMERGENCY_STOP, view.emergencyStop() ? Light.flashing(RED_FULL) : Light.of(RED_LOW));
        frame.top(TOP_SESSION, view.sessionActive() ? Light.of(GREEN_LOW) : Light.OFF);
        Light compact;
        if (view.compacting()) {
            compact = Light.flashing(ORANGE);
        } else if (view.compactionRequested()) {
            compact = Light.flashing(AMBER_FULL);
        } else {
            compact = Light.of(AMBER_LOW);
        }
        frame.top(TOP_COMPACT, compact);
    }
}
