package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import io.github.kltyton.xaeroearth.client.chunkmap.geometry.QuadCapture;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** KUI exposes tessellated meshes, not the intermediate quads. Keep OBJ/model decisions in KUI. */
@Mixin(targets = "io.github.kltyton.kltytonui.chunkmap.KuiChunkMeshBaker$VertexStream", remap = false)
abstract class KuiFaceStreamMixin {
    @Inject(method = "quad", at = @At("HEAD"))
    private void earth$faceMaterial(ByteBuffer quad, CallbackInfo callback) { QuadCapture.writeMaterial(quad); }

    @ModifyExpressionValue(method = "quad", at = @At(value = "FIELD",
            target = "Lio/github/kltyton/kltytonui/chunkmap/KuiChunkMeshBaker;TRIANGLE_VERTICES:[I"), require = 0)
    private int[] earth$quadPacket(int[] triangles) { return QuadCapture.vertices(triangles); }
}
