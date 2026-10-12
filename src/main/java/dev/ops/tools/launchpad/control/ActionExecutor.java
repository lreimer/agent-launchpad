package dev.ops.tools.launchpad.control;

import dev.ops.tools.launchpad.LaunchpadConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Executes the optional OS commands configured for the top row buttons, e.g. to send SIGINT to the agent
 * process or to type {@code /compact} into a tmux pane. Commands are configured as argv lists and are
 * executed without a shell.
 */
@ApplicationScoped
public class ActionExecutor {

    private static final Logger LOGGER = Logger.getLogger(ActionExecutor.class);

    @Inject
    LaunchpadConfig config;

    public void emergencyStop() {
        run("emergency stop", config.actions().emergencyStopCommand());
    }

    public void compact() {
        run("compaction", config.actions().compactCommand());
    }

    private static void run(String name, Optional<List<String>> command) {
        if (command.isEmpty() || command.get().isEmpty()) {
            return;
        }
        try {
            new ProcessBuilder(command.get())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            LOGGER.infof("Started %s command %s", name, command.get());
        } catch (IOException e) {
            LOGGER.warnf(e, "Unable to run %s command %s", name, command.get());
        }
    }
}
