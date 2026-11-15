package io.github.kltyton.xaeroearth.client.integration.companion;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

/** Optional client integration. The provider owns server authorization and all companion movement. */
public interface CompanionMapAccess {
    @FunctionalInterface
    interface MoveRequest {
        boolean send(ClientLevel level, Collection<UUID> companions, BlockPos goal);
    }

    boolean available(ClientLevel level);

    /** Returns currently controllable companions with authoritative positions in this client level. */
    Collection<? extends Entity> visibleCompanions(ClientLevel level);

    /** Submits the selection to the server-side companion navigation service. */
    boolean move(ClientLevel level, Set<UUID> selection, BlockPos goal);

    void clearSnapshot();
}
