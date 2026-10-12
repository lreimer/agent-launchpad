package dev.ops.tools.launchpad.midi;

/**
 * The Launchpad Mini MK2 has bi-colour (red/green) LEDs, each with brightness 0..3. All colours of the
 * visual design are expressed with these primitives (see README for the palette mapping).
 */
public enum LaunchpadColor {
    OFF(0, 0),

    RED_LOW(1, 0),
    RED(2, 0),
    RED_FULL(3, 0),

    GREEN_LOW(0, 1),
    GREEN(0, 2),
    GREEN_FULL(0, 3),

    AMBER_LOW(1, 1),
    AMBER(2, 2),
    AMBER_FULL(3, 3),

    ORANGE(3, 2),
    YELLOW(2, 3);

    private static final int FLAGS_NORMAL = 12;
    private static final int FLAGS_FLASHING = 8;

    private final int red;
    private final int green;

    LaunchpadColor(int red, int green) {
        this.red = red;
        this.green = green;
    }

    /**
     * MIDI velocity for this colour: {@code 16 * green + red + flags}. Normal colours copy into both
     * buffers, flashing colours clear the other buffer so the device toggles them automatically.
     */
    public int velocity(boolean flashing) {
        if (this == OFF) {
            return FLAGS_NORMAL;
        }
        return 16 * green + red + (flashing ? FLAGS_FLASHING : FLAGS_NORMAL);
    }

    public int red() {
        return red;
    }

    public int green() {
        return green;
    }
}
