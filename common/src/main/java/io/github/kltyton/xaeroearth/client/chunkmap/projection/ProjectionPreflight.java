package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTiles;
import java.util.Map;

/** Separates visible cached coverage from whether a replacement mesh still needs to load. */
public final class ProjectionPreflight {
    static final int READY = 1, DISPLAYABLE = 2, READING = 4, STATIC_READY = 8;
    static final int STATIC = READY | DISPLAYABLE | READING | STATIC_READY;
    public static final ProjectionPreflight NONE = new ProjectionPreflight(Map.of());

    public enum State { READY, STATIC_READY, DISPLAYABLE, READING, LIVE }

    private final Map<ChunkTiles.Tile, Integer> flags;

    // The frame plan transfers ownership of the completed flags map.
    ProjectionPreflight(Map<ChunkTiles.Tile, Integer> flags) { this.flags = flags; }

    public State state(ChunkTiles.Tile tile) {
        int value = flags.getOrDefault(tile, 0);
        if ((value & READY) != 0) return State.READY;
        if ((value & STATIC_READY) != 0) return State.STATIC_READY;
        if ((value & DISPLAYABLE) != 0) return State.DISPLAYABLE;
        return (value & READING) != 0 ? State.READING : State.LIVE;
    }
}
