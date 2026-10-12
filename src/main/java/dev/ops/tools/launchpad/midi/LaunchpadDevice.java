package dev.ops.tools.launchpad.midi;

import javax.sound.midi.ShortMessage;

/**
 * Writes {@link LaunchpadFrame}s to a Launchpad Mini MK2, only sending MIDI messages for LEDs that changed.
 * <p>
 * Grid pads and the right column are addressed with NoteOn ({@code key = 16 * row + col}, column 8 is the
 * right column), the top row with Control Change 104-111.
 */
public class LaunchpadDevice {

    /** Minimal MIDI output abstraction, makes the device testable without hardware. */
    @FunctionalInterface
    public interface MidiOutput {
        void send(int command, int data1, int data2);
    }

    public static final int TOP_ROW_FIRST_CC = 104;

    private static final int CC_CONTROL = 0;
    private static final int CONTROL_RESET = 0;
    private static final int CONTROL_AUTO_FLASH = 40;

    private final MidiOutput output;
    private LaunchpadFrame current;

    public LaunchpadDevice(MidiOutput output) {
        this.output = output;
    }

    /** Resets the device and enables the automatic flashing mode used for pulsing LEDs. */
    public synchronized void initialize() {
        output.send(ShortMessage.CONTROL_CHANGE, CC_CONTROL, CONTROL_RESET);
        output.send(ShortMessage.CONTROL_CHANGE, CC_CONTROL, CONTROL_AUTO_FLASH);
        current = new LaunchpadFrame();
    }

    /** Switches all LEDs off. */
    public synchronized void reset() {
        output.send(ShortMessage.CONTROL_CHANGE, CC_CONTROL, CONTROL_RESET);
        current = new LaunchpadFrame();
    }

    public synchronized void apply(LaunchpadFrame frame) {
        LaunchpadFrame previous = current;
        for (int row = 0; row < LaunchpadFrame.SIZE; row++) {
            for (int col = 0; col < LaunchpadFrame.SIZE; col++) {
                Light light = frame.grid(row, col);
                if (previous == null || !light.equals(previous.grid(row, col))) {
                    note(row, col, light);
                }
            }
            Light side = frame.side(row);
            if (previous == null || !side.equals(previous.side(row))) {
                note(row, LaunchpadFrame.SIZE, side);
            }
            Light top = frame.top(row);
            if (previous == null || !top.equals(previous.top(row))) {
                output.send(ShortMessage.CONTROL_CHANGE, TOP_ROW_FIRST_CC + row, velocity(top));
            }
        }
        current = frame;
    }

    private void note(int row, int col, Light light) {
        output.send(ShortMessage.NOTE_ON, row * 16 + col, velocity(light));
    }

    private static int velocity(Light light) {
        return light.color().velocity(light.flashing());
    }
}
