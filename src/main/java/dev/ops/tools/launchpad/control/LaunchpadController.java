package dev.ops.tools.launchpad.control;

import dev.ops.tools.launchpad.midi.ButtonPress;
import dev.ops.tools.launchpad.state.AgentStateService;
import dev.ops.tools.launchpad.state.ApprovalDecision;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Maps Launchpad button presses (hardware or virtual) to control actions:
 * <ul>
 *     <li>Pad (1,1): allow, pad (1,2): block, pad (1,3): allow for the rest of the session</li>
 *     <li>Side buttons of rows 3-6: cancel the subagent of that strip</li>
 *     <li>Top row button 1 (CC 104): emergency stop, button 8 (CC 111): manual compaction</li>
 * </ul>
 */
@ApplicationScoped
public class LaunchpadController {

    private static final Logger LOGGER = Logger.getLogger(LaunchpadController.class);

    @Inject
    AgentStateService state;

    @Inject
    ActionExecutor actions;

    public void onPress(@Observes ButtonPress press) {
        LOGGER.debugf("Button pressed: %s", press);
        switch (press.kind()) {
            case GRID -> {
                if (press.row() == 0 && press.col() <= 2) {
                    ApprovalDecision decision = switch (press.col()) {
                        case 0 -> ApprovalDecision.ALLOW;
                        case 1 -> ApprovalDecision.DENY;
                        default -> ApprovalDecision.ALLOW_SESSION;
                    };
                    state.resolve(decision);
                }
            }
            case SIDE -> {
                int strip = press.row() - LaunchpadRenderer.FIRST_STRIP_ROW;
                if (strip >= 0 && strip < AgentStateService.SUBAGENT_STRIPS) {
                    state.cancelSubagent(strip);
                }
            }
            case TOP -> {
                if (press.col() == LaunchpadRenderer.TOP_EMERGENCY_STOP) {
                    if (state.toggleEmergencyStop()) {
                        actions.emergencyStop();
                    }
                } else if (press.col() == LaunchpadRenderer.TOP_COMPACT) {
                    if (state.toggleCompactionRequest()) {
                        actions.compact();
                    }
                }
            }
        }
    }
}
