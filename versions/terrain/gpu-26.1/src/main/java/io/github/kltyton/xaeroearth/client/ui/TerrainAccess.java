package io.github.kltyton.xaeroearth.client.ui;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene;
import net.minecraft.client.Minecraft;

abstract class TerrainAccess {
    protected static NativeTerrainScene renderer;
    protected static RenderTarget mainTarget(Minecraft minecraft) {
        return minecraft.getMainRenderTarget();
    }
}
