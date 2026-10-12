package dev.ops.tools.launchpad.control;

import dev.ops.tools.launchpad.midi.LaunchpadColor;
import dev.ops.tools.launchpad.midi.LaunchpadFrame;
import dev.ops.tools.launchpad.midi.Light;
import dev.ops.tools.launchpad.state.ActivityState;
import dev.ops.tools.launchpad.state.LaunchpadView;
import dev.ops.tools.launchpad.state.TurnState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchpadRendererTest {

    private static final List<LaunchpadView.Strip> NO_STRIPS = Arrays.asList(null, null, null, null);

    @Test
    void pendingApprovalsLightTheDecisionZone() {
        LaunchpadFrame frame = LaunchpadRenderer.render(view(3, TurnState.THINKING, 0, null, false, NO_STRIPS,
                List.of(), 0, false, false), 0);
        assertEquals(Light.flashing(LaunchpadColor.AMBER_FULL), frame.grid(0, 0));
        assertEquals(Light.of(LaunchpadColor.RED_FULL), frame.grid(0, 1));
        assertEquals(Light.of(LaunchpadColor.YELLOW), frame.grid(0, 2));
        assertEquals(Light.of(LaunchpadColor.AMBER_LOW), frame.grid(1, 0));
        assertEquals(Light.of(LaunchpadColor.AMBER_LOW), frame.grid(1, 1));
        assertEquals(Light.OFF, frame.grid(1, 2));
    }

    @Test
    void turnStates() {
        assertEquals(Light.flashing(LaunchpadColor.AMBER_LOW), topLeft(TurnState.THINKING, 0));
        assertEquals(Light.of(LaunchpadColor.AMBER_FULL), topLeft(TurnState.STOPPED, 100));
        assertEquals(Light.of(LaunchpadColor.GREEN_FULL), topLeft(TurnState.STOPPED, 5000));
        assertEquals(Light.flashing(LaunchpadColor.RED_FULL), topLeft(TurnState.FAILED, 0));
        assertEquals(Light.OFF, topLeft(TurnState.IDLE, 0));
    }

    @Test
    void subagentStrips() {
        List<LaunchpadView.Strip> strips = Arrays.asList(
                new LaunchpadView.Strip("a", "Explore", ActivityState.RUNNING),
                new LaunchpadView.Strip("b", "Plan", ActivityState.WAITING),
                new LaunchpadView.Strip("c", "Review", ActivityState.SUCCESS),
                null);
        LaunchpadFrame frame = LaunchpadRenderer.render(view(0, TurnState.THINKING, 0, null, false, strips,
                List.of(), 0, false, false), 3);
        // chasing animation: head at column 3 for tick 3
        assertEquals(Light.of(LaunchpadColor.GREEN_FULL), frame.grid(2, 3));
        assertEquals(Light.of(LaunchpadColor.GREEN), frame.grid(2, 2));
        assertEquals(Light.of(LaunchpadColor.GREEN_LOW), frame.grid(2, 1));
        assertEquals(Light.OFF, frame.grid(2, 5));
        assertEquals(Light.of(LaunchpadColor.YELLOW), frame.grid(3, 0));
        assertEquals(Light.of(LaunchpadColor.GREEN), frame.grid(4, 7));
        assertEquals(Light.OFF, frame.grid(5, 0));
    }

    @Test
    void historyStreamAndGauge() {
        LaunchpadFrame frame = LaunchpadRenderer.render(view(0, TurnState.THINKING, 0, 0.8, false, NO_STRIPS,
                List.of(ActivityState.SUCCESS, ActivityState.FAILURE, ActivityState.DENIED, ActivityState.RUNNING),
                3, false, false), 0);
        assertEquals(Light.of(LaunchpadColor.GREEN_FULL), frame.grid(6, 0));
        assertEquals(Light.of(LaunchpadColor.RED_FULL), frame.grid(6, 1));
        assertEquals(Light.of(LaunchpadColor.AMBER_FULL), frame.grid(6, 2));
        assertEquals(Light.flashing(LaunchpadColor.YELLOW), frame.grid(6, 3));
        assertEquals(Light.OFF, frame.grid(6, 4));

        assertEquals(Light.of(LaunchpadColor.AMBER_LOW), frame.grid(7, 2));
        assertEquals(Light.OFF, frame.grid(7, 3));

        // 80% of the context window lights 7 of 8 gauge LEDs from the bottom
        assertEquals(Light.of(LaunchpadColor.GREEN_FULL), frame.side(7));
        assertEquals(Light.of(LaunchpadColor.AMBER_FULL), frame.side(3));
        assertEquals(Light.of(LaunchpadColor.RED_FULL), frame.side(1));
        assertEquals(Light.OFF, frame.side(0));
    }

    @Test
    void compactionAndTopRow() {
        LaunchpadFrame frame = LaunchpadRenderer.render(view(0, TurnState.IDLE, 0, 0.5, true, NO_STRIPS,
                List.of(), 0, false, true), 0);
        for (int row = 0; row < 8; row++) {
            assertEquals(Light.flashing(LaunchpadColor.ORANGE), frame.side(row));
        }
        assertEquals(Light.flashing(LaunchpadColor.RED_FULL), frame.top(LaunchpadRenderer.TOP_EMERGENCY_STOP));
        assertEquals(Light.flashing(LaunchpadColor.ORANGE), frame.top(LaunchpadRenderer.TOP_COMPACT));
        assertEquals(Light.of(LaunchpadColor.GREEN_LOW), frame.top(LaunchpadRenderer.TOP_SESSION));
    }

    private static Light topLeft(TurnState turn, long age) {
        return LaunchpadRenderer.render(view(0, turn, age, null, false, NO_STRIPS, List.of(), 0, false, false), 0)
                .grid(0, 0);
    }

    private static LaunchpadView view(int pending, TurnState turn, long age, Double context, boolean compacting,
                                      List<LaunchpadView.Strip> strips, List<ActivityState> history,
                                      int streamPads, boolean streaming, boolean emergency) {
        return new LaunchpadView(pending, turn, age, context, compacting, strips, history, streamPads, streaming,
                emergency, false, true);
    }
}
