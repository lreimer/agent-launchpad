package dev.ops.tools.launchpad.midi;

/**
 * State of a single LED.
 */
public record Light(LaunchpadColor color, boolean flashing) {

    public static final Light OFF = new Light(LaunchpadColor.OFF, false);

    public Light {
        if (color == LaunchpadColor.OFF) {
            flashing = false;
        }
    }

    public static Light of(LaunchpadColor color) {
        return new Light(color, false);
    }

    public static Light flashing(LaunchpadColor color) {
        return new Light(color, true);
    }
}
