package dev.ops.tools.launchpad;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Type-safe configuration of the agent launchpad daemon.
 */
@ConfigMapping(prefix = "launchpad")
public interface LaunchpadConfig {

    Midi midi();

    Approval approval();

    Context context();

    Stream stream();

    Actions actions();

    Render render();

    interface Midi {
        /** Whether to connect to a physical Launchpad via javax.sound.midi. */
        @WithDefault("true")
        boolean enabled();

        /** Case-insensitive substring of the MIDI device name. */
        @WithDefault("Launchpad Mini")
        String deviceName();
    }

    interface Approval {
        /** How long a blocking hook waits for a pad press before falling back to the normal permission flow. */
        @WithDefault("PT2M")
        Duration timeout();

        /** Regular expression of tool names that require an interactive approval on the Launchpad. */
        @WithDefault(".*")
        String toolPattern();
    }

    interface Context {
        /** Size of the model context window used to scale the right column gauge. */
        @WithDefault("200000")
        long windowTokens();
    }

    interface Stream {
        /** Approximate number of streamed tokens represented by one lit pad in the telemetry row. */
        @WithDefault("100")
        int tokensPerPad();
    }

    interface Render {
        /** Interval of the LED render loop, drives the chasing and ripple animations. */
        @WithDefault("125ms")
        Duration interval();
    }

    interface Actions {
        /** Optional command (argv) executed when the emergency stop is engaged. */
        Optional<List<String>> emergencyStopCommand();

        /** Optional command (argv) executed when a manual compaction is requested. */
        Optional<List<String>> compactCommand();
    }
}
