package dev.ops.tools.launchpad.control;

import dev.ops.tools.launchpad.midi.LaunchpadFrame;
import dev.ops.tools.launchpad.midi.MidiConnector;
import dev.ops.tools.launchpad.state.AgentStateService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Periodically renders the agent state and pushes changed LEDs to the Launchpad. The tick drives the chasing
 * and ripple animations, flashing LEDs are toggled by the device itself.
 */
@ApplicationScoped
public class RenderLoop {

    @Inject
    AgentStateService state;

    @Inject
    MidiConnector midi;

    private long tick;
    private volatile LaunchpadFrame latest = new LaunchpadFrame();

    @Scheduled(every = "${launchpad.render.interval:0.125s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    synchronized void render() {
        LaunchpadFrame frame = LaunchpadRenderer.render(state.view(), tick++);
        latest = frame;
        midi.device().ifPresent(device -> device.apply(frame));
    }

    /** The most recently rendered frame, used by the virtual Launchpad. */
    public LaunchpadFrame latest() {
        return latest;
    }
}
