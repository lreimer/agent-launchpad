package dev.ops.tools.launchpad.midi;

import org.junit.jupiter.api.Test;

import javax.sound.midi.ShortMessage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchpadDeviceTest {

    private final List<int[]> messages = new ArrayList<>();
    private final LaunchpadDevice device = new LaunchpadDevice((command, data1, data2) -> messages.add(new int[]{command, data1, data2}));

    @Test
    void colorVelocities() {
        assertEquals(12, LaunchpadColor.OFF.velocity(false));
        assertEquals(15, LaunchpadColor.RED_FULL.velocity(false));
        assertEquals(60, LaunchpadColor.GREEN_FULL.velocity(false));
        assertEquals(63, LaunchpadColor.AMBER_FULL.velocity(false));
        assertEquals(62, LaunchpadColor.YELLOW.velocity(false));
        assertEquals(59, LaunchpadColor.AMBER_FULL.velocity(true));
        assertEquals(12, LaunchpadColor.OFF.velocity(true));
    }

    @Test
    void initializeResetsAndEnablesFlashing() {
        device.initialize();
        assertEquals(2, messages.size());
        assertMessage(messages.get(0), ShortMessage.CONTROL_CHANGE, 0, 0);
        assertMessage(messages.get(1), ShortMessage.CONTROL_CHANGE, 0, 40);
    }

    @Test
    void onlyChangedLedsAreSent() {
        LaunchpadFrame frame = new LaunchpadFrame();
        device.apply(frame);
        assertEquals(64 + 8 + 8, messages.size());

        messages.clear();
        device.apply(new LaunchpadFrame());
        assertTrue(messages.isEmpty());

        device.apply(new LaunchpadFrame()
                .grid(2, 3, Light.of(LaunchpadColor.GREEN_FULL))
                .side(7, Light.flashing(LaunchpadColor.RED_FULL))
                .top(0, Light.of(LaunchpadColor.RED_LOW)));
        assertEquals(3, messages.size());
        assertSent(ShortMessage.NOTE_ON, 2 * 16 + 3, 60);
        assertSent(ShortMessage.NOTE_ON, 7 * 16 + 8, 11);
        assertSent(ShortMessage.CONTROL_CHANGE, 104, 13);
    }

    @Test
    void decodeInput() {
        assertEquals(ButtonPress.grid(0, 0), LaunchpadInput.decode(ShortMessage.NOTE_ON, 0, 127).orElseThrow());
        assertEquals(ButtonPress.grid(7, 7), LaunchpadInput.decode(ShortMessage.NOTE_ON, 0x77, 127).orElseThrow());
        assertEquals(ButtonPress.side(2), LaunchpadInput.decode(ShortMessage.NOTE_ON, 0x28, 127).orElseThrow());
        assertEquals(ButtonPress.top(0), LaunchpadInput.decode(ShortMessage.CONTROL_CHANGE, 104, 127).orElseThrow());
        assertEquals(ButtonPress.top(7), LaunchpadInput.decode(ShortMessage.CONTROL_CHANGE, 111, 127).orElseThrow());
        assertTrue(LaunchpadInput.decode(ShortMessage.NOTE_ON, 0, 0).isEmpty());
        assertTrue(LaunchpadInput.decode(ShortMessage.CONTROL_CHANGE, 103, 127).isEmpty());
        assertTrue(LaunchpadInput.decode(ShortMessage.NOTE_ON, 0x09, 127).isEmpty());
    }

    private void assertSent(int command, int data1, int data2) {
        assertTrue(messages.stream().anyMatch(m -> m[0] == command && m[1] == data1 && m[2] == data2),
                "MIDI message " + command + " " + data1 + " " + data2 + " not sent");
    }

    private static void assertMessage(int[] message, int command, int data1, int data2) {
        assertEquals(command, message[0]);
        assertEquals(data1, message[1]);
        assertEquals(data2, message[2]);
    }
}
