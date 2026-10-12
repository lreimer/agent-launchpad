package dev.ops.tools.launchpad.midi;

/**
 * A button press on the Launchpad. For {@link Kind#GRID} both row and column are set, for
 * {@link Kind#SIDE} and {@link Kind#TOP} only {@code index} (0..7) is relevant.
 */
public record ButtonPress(Kind kind, int row, int col) {

    public enum Kind {
        GRID, SIDE, TOP
    }

    public static ButtonPress grid(int row, int col) {
        return new ButtonPress(Kind.GRID, row, col);
    }

    public static ButtonPress side(int row) {
        return new ButtonPress(Kind.SIDE, row, LaunchpadFrame.SIZE);
    }

    public static ButtonPress top(int index) {
        return new ButtonPress(Kind.TOP, -1, index);
    }

    public int index() {
        return kind == Kind.TOP ? col : row;
    }
}
