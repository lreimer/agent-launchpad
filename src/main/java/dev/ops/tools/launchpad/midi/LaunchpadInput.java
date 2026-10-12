package dev.ops.tools.launchpad.midi;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * MIDI {@link Receiver} translating Launchpad Mini MK2 input messages into {@link ButtonPress} events.
 * Only presses are reported, releases (velocity / value 0) are ignored.
 */
public class LaunchpadInput implements Receiver {

    private final Consumer<ButtonPress> listener;

    public LaunchpadInput(Consumer<ButtonPress> listener) {
        this.listener = listener;
    }

    @Override
    public void send(MidiMessage message, long timeStamp) {
        if (message instanceof ShortMessage shortMessage) {
            decode(shortMessage.getCommand(), shortMessage.getData1(), shortMessage.getData2())
                    .ifPresent(listener);
        }
    }

    public static Optional<ButtonPress> decode(int command, int data1, int data2) {
        if (data2 == 0) {
            return Optional.empty();
        }
        if (command == ShortMessage.NOTE_ON) {
            int row = data1 / 16;
            int col = data1 % 16;
            if (row >= LaunchpadFrame.SIZE) {
                return Optional.empty();
            }
            if (col < LaunchpadFrame.SIZE) {
                return Optional.of(ButtonPress.grid(row, col));
            }
            if (col == LaunchpadFrame.SIZE) {
                return Optional.of(ButtonPress.side(row));
            }
        } else if (command == ShortMessage.CONTROL_CHANGE) {
            int index = data1 - LaunchpadDevice.TOP_ROW_FIRST_CC;
            if (index >= 0 && index < LaunchpadFrame.SIZE) {
                return Optional.of(ButtonPress.top(index));
            }
        }
        return Optional.empty();
    }

    @Override
    public void close() {
        // nothing to release
    }
}
