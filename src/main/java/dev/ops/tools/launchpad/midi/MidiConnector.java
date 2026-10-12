package dev.ops.tools.launchpad.midi;

import dev.ops.tools.launchpad.LaunchpadConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import java.util.Locale;
import java.util.Optional;

/**
 * Discovers the Launchpad Mini MK2 via javax.sound.midi, wires its input to CDI {@link ButtonPress} events
 * and exposes a {@link LaunchpadDevice} for LED output. If no device is found the daemon keeps running
 * headless, the virtual Launchpad REST API can still be used.
 */
@ApplicationScoped
public class MidiConnector {

    private static final Logger LOGGER = Logger.getLogger(MidiConnector.class);

    @Inject
    LaunchpadConfig config;

    @Inject
    Event<ButtonPress> buttonPresses;

    private MidiDevice inputDevice;
    private MidiDevice outputDevice;
    private volatile LaunchpadDevice launchpad;

    void onStart(@Observes StartupEvent event) {
        if (config.midi().enabled()) {
            connect();
        } else {
            LOGGER.info("MIDI disabled, running with the virtual Launchpad only.");
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        disconnect();
    }

    public Optional<LaunchpadDevice> device() {
        return Optional.ofNullable(launchpad);
    }

    synchronized void connect() {
        String wanted = config.midi().deviceName().toLowerCase(Locale.ROOT);
        try {
            for (MidiDevice.Info info : MidiSystem.getMidiDeviceInfo()) {
                LOGGER.debugf("Found MIDI device %s (%s) - %s", info.getName(), info.getVendor(), info.getDescription());
                if (!info.getName().toLowerCase(Locale.ROOT).contains(wanted)) {
                    continue;
                }
                MidiDevice device = MidiSystem.getMidiDevice(info);
                if (inputDevice == null && device.getMaxTransmitters() != 0) {
                    inputDevice = device;
                } else if (outputDevice == null && device.getMaxReceivers() != 0) {
                    outputDevice = device;
                }
            }
            if (inputDevice == null || outputDevice == null) {
                LOGGER.warnf("No MIDI device matching '%s' found, running with the virtual Launchpad only.",
                        config.midi().deviceName());
                inputDevice = null;
                outputDevice = null;
                return;
            }

            inputDevice.open();
            outputDevice.open();
            inputDevice.getTransmitter().setReceiver(new LaunchpadInput(buttonPresses::fire));
            Receiver receiver = outputDevice.getReceiver();
            LaunchpadDevice device = new LaunchpadDevice((command, data1, data2) -> send(receiver, command, data1, data2));
            device.initialize();
            launchpad = device;
            LOGGER.infof("Connected to Launchpad '%s'.", outputDevice.getDeviceInfo().getName());
        } catch (MidiUnavailableException | RuntimeException e) {
            LOGGER.warn("Unable to connect to the Launchpad, running with the virtual Launchpad only.", e);
            disconnect();
        }
    }

    synchronized void disconnect() {
        LaunchpadDevice device = launchpad;
        launchpad = null;
        if (device != null) {
            device.reset();
        }
        if (inputDevice != null) {
            inputDevice.close();
            inputDevice = null;
        }
        if (outputDevice != null) {
            outputDevice.close();
            outputDevice = null;
        }
    }

    private static void send(Receiver receiver, int command, int data1, int data2) {
        try {
            receiver.send(new ShortMessage(command, 0, data1, data2), -1);
        } catch (InvalidMidiDataException e) {
            LOGGER.warnf(e, "Could not send MIDI message %d %d %d", command, data1, data2);
        }
    }
}
