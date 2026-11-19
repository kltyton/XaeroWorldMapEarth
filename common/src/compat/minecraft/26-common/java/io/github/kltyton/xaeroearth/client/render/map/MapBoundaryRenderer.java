package io.github.kltyton.xaeroearth.client.render.map;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.NativeTerrainScene.Surface;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.DynamicUniformStorage;

/** Blends an atlas edge into the map color while sampling its unattached depth. */
public final class MapBoundaryRenderer implements AutoCloseable {
    private DynamicUniformStorage<Uniform> uniforms;

    public void draw(RenderTarget target, MapCamera camera, Surface surface, GpuTextureView field) {
        if (uniforms == null) uniforms = new DynamicUniformStorage<>("Earth explored boundary", 128, 2);
        uniforms.endFrame();
        boolean zeroToOne = MapFogPlatform.zeroToOne();
        var projection = MapFogPlatform.clearDepth() == 0 ? camera.nativeProjectionMatrix(zeroToOne)
                : camera.nativeForwardProjectionMatrix(zeroToOne);
        var view = MapBoundaryView.of(camera, surface, projection);
        var uniform = uniforms.writeUniform(new Uniform(view, MapFogPlatform.clearDepth(), zeroToOne));
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = MapFogPlatform.openBoundary(target)) {
            pass.setPipeline(MapFogPlatform.BOUNDARY);
            pass.setUniform("MapBoundaryParameters", uniform);
            pass.bindTexture("TerrainDepth", target.getDepthTextureView(), nearest);
            pass.bindTexture("BoundaryField", field, nearest);
            MapFogPlatform.draw(pass);
        }
    }

    @Override public void close() {
        if (uniforms != null) { uniforms.close(); uniforms = null; }
    }

    private record Uniform(MapBoundaryView view, float clear, boolean zeroToOne)
            implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer out) {
            view.inverse().get(out.position(), out);
            out.position(out.position() + 64);
            out.putFloat(view.x()).putFloat(view.z()).putFloat(view.step()).putFloat(view.fieldRow());
            out.putFloat(view.width()).putFloat(view.height()).putFloat(view.feather()).putFloat(view.aspect());
            out.putFloat(clear).putFloat(zeroToOne ? 1 : 0).putFloat(MapFogPlatform.packedHeight() ? 1 : 0).putFloat(0);
            out.putFloat(view.cameraY()).putFloat(view.slopeX()).putFloat(view.slopeZ()).putFloat(0);
        }
    }
}
