package dev.ops.tools.launchpad.midi;

import java.util.Arrays;

/**
 * Complete LED image of the Launchpad Mini MK2: 8x8 grid, right column (scene launch) and top row (CC 104-111).
 * Rows and columns are zero based, row 0 is the top grid row.
 */
public final class LaunchpadFrame {

    public static final int SIZE = 8;

    private final Light[] grid = new Light[SIZE * SIZE];
    private final Light[] side = new Light[SIZE];
    private final Light[] top = new Light[SIZE];

    public LaunchpadFrame() {
        Arrays.fill(grid, Light.OFF);
        Arrays.fill(side, Light.OFF);
        Arrays.fill(top, Light.OFF);
    }

    public LaunchpadFrame grid(int row, int col, Light light) {
        grid[index(row, col)] = light;
        return this;
    }

    public Light grid(int row, int col) {
        return grid[index(row, col)];
    }

    public LaunchpadFrame side(int row, Light light) {
        side[check(row)] = light;
        return this;
    }

    public Light side(int row) {
        return side[check(row)];
    }

    public LaunchpadFrame top(int index, Light light) {
        top[check(index)] = light;
        return this;
    }

    public Light top(int index) {
        return top[check(index)];
    }

    private static int index(int row, int col) {
        return check(row) * SIZE + check(col);
    }

    private static int check(int value) {
        if (value < 0 || value >= SIZE) {
            throw new IndexOutOfBoundsException("Launchpad coordinate out of range: " + value);
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LaunchpadFrame other
                && Arrays.equals(grid, other.grid)
                && Arrays.equals(side, other.side)
                && Arrays.equals(top, other.top);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(grid) + Arrays.hashCode(side)) + Arrays.hashCode(top);
    }
}
