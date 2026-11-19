package io.github.kltyton.xaeroearth.client.render.map;

import com.mojang.blaze3d.pipeline.RenderTarget;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.DynamicUniformStorage;

/** Submits an empty-depth-only fog pass through the native graphics device. */
public final class MapFogRenderer implements AutoCloseable {
    private DynamicUniformStorage<Uniform> uniforms;

    public void draw(RenderTarget target, MapCamera camera, boolean cave) {
        if (uniforms == null) uniforms = new DynamicUniformStorage<>("Earth fog", 16, 2);
        uniforms.endFrame();
        var fog = MapFogView.of(camera, cave, MapFogPlatform.farPlane());
        var buffer = uniforms.writeUniform(new Uniform(fog));
        try (var pass = MapFogPlatform.open(target)) {
            pass.setPipeline(MapFogPlatform.PIPELINE);
            pass.setUniform("MapFogParameters", buffer);
            MapFogPlatform.draw(pass);
        }
    }

    @Override public void close() {
        if (uniforms != null) { uniforms.close(); uniforms = null; }
    }

    private record Uniform(MapFogView f) implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer out) {
            out.putFloat(f.aspect()).putFloat(f.cave()).putFloat(f.far()).putFloat(0);
        }
    }
}
