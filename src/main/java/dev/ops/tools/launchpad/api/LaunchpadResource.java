package dev.ops.tools.launchpad.api;

import dev.ops.tools.launchpad.control.RenderLoop;
import dev.ops.tools.launchpad.midi.ButtonPress;
import dev.ops.tools.launchpad.midi.LaunchpadFrame;
import dev.ops.tools.launchpad.midi.Light;
import dev.ops.tools.launchpad.state.AgentStateService;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.List;

/**
 * Virtual Launchpad: inspect the current LED frame and simulate button presses without hardware.
 * Coordinates are 1-based, matching the documented layout, e.g. pad (1,1) is the top left pad.
 */
@Path("/api/v1/launchpad")
@Produces(MediaType.APPLICATION_JSON)
public class LaunchpadResource {

    /** LED state as colour name, a trailing {@code *} marks flashing LEDs. */
    public record FrameView(List<List<String>> grid, List<String> side, List<String> top,
                            List<PendingView> pendingApprovals) {
    }

    public record PendingView(long id, String source, String type, String sessionId, String agentId,
                              String toolName) {
    }

    @Inject
    RenderLoop renderLoop;

    @Inject
    AgentStateService state;

    @Inject
    Event<ButtonPress> buttonPresses;

    @GET
    public FrameView frame() {
        LaunchpadFrame frame = renderLoop.latest();
        List<List<String>> grid = new ArrayList<>();
        List<String> side = new ArrayList<>();
        List<String> top = new ArrayList<>();
        for (int row = 0; row < LaunchpadFrame.SIZE; row++) {
            List<String> cols = new ArrayList<>();
            for (int col = 0; col < LaunchpadFrame.SIZE; col++) {
                cols.add(name(frame.grid(row, col)));
            }
            grid.add(cols);
            side.add(name(frame.side(row)));
            top.add(name(frame.top(row)));
        }
        List<PendingView> pending = state.pendingApprovals().stream()
                .map(p -> new PendingView(p.id(), p.event().source().name(), p.event().type().name(),
                        p.event().sessionId(), p.event().agentId(), p.event().toolName()))
                .toList();
        return new FrameView(grid, side, top, pending);
    }

    @POST
    @Path("pads/{row}/{col}")
    public void pad(@PathParam("row") int row, @PathParam("col") int col) {
        buttonPresses.fire(ButtonPress.grid(index(row), index(col)));
    }

    @POST
    @Path("side/{row}")
    public void side(@PathParam("row") int row) {
        buttonPresses.fire(ButtonPress.side(index(row)));
    }

    @POST
    @Path("top/{button}")
    public void top(@PathParam("button") int button) {
        buttonPresses.fire(ButtonPress.top(index(button)));
    }

    private static int index(int oneBased) {
        if (oneBased < 1 || oneBased > LaunchpadFrame.SIZE) {
            throw new BadRequestException("Launchpad coordinates must be between 1 and 8");
        }
        return oneBased - 1;
    }

    private static String name(Light light) {
        return light.color().name() + (light.flashing() ? "*" : "");
    }
}
