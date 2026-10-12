package dev.ops.tools.launchpad.control;

import dev.ops.tools.launchpad.LaunchpadConfig;
import dev.ops.tools.launchpad.midi.LaunchpadFrame;
import dev.ops.tools.launchpad.midi.MidiConnector;
import dev.ops.tools.launchpad.state.AgentStateService;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically renders the agent state and pushes changed LEDs to the Launchpad. The tick drives the chasing
 * and ripple animations, flashing LEDs are toggled by the device itself. A dedicated timer thread is used
 * because the Quarkus scheduler does not support sub-second intervals.
 */
@ApplicationScoped
public class RenderLoop {

    @Inject
    AgentStateService state;

    @Inject
    MidiConnector midi;

    @Inject
    LaunchpadConfig config;

    private long tick;
    private volatile LaunchpadFrame latest = new LaunchpadFrame();
    private ScheduledExecutorService timer;

    // runs after the MIDI connector has been initialized
    void onStart(@Observes @Priority(2000) StartupEvent event) {
        long millis = Math.max(20, config.render().interval().toMillis());
        timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("launchpad-render").daemon().factory());
        timer.scheduleAtFixedRate(this::safeRender, 0, millis, TimeUnit.MILLISECONDS);
    }

    void onStop(@Observes @Priority(1) ShutdownEvent event) {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    private void safeRender() {
        try {
            render();
        } catch (RuntimeException e) {
            // an exception would cancel all further executions of the timer
            Log.warn("Failed to render the Launchpad", e);
        }
    }

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
