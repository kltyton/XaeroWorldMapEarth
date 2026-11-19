package io.github.kltyton.xaeroearth.client.render.map;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.joml.Matrix4f;

/** Blends XaeroPlus chunk colors onto the terrain depth for the active camera. */
public final class MapHighlightRenderer implements AutoCloseable {
    private MapHighlightField uploaded;
    private DynamicTexture atlas;
    private DynamicUniformStorage<Uniform> uniforms;

    public void draw(RenderTarget target, MapCamera camera, MapHighlightField field) {
        if (field == null) return;
        if (field != uploaded) upload(field);
        if (uniforms == null) uniforms = new DynamicUniformStorage<>("Earth map highlights", 112, 2);
        uniforms.endFrame();
        boolean zeroToOne = MapFogPlatform.zeroToOne();
        float clear = MapFogPlatform.clearDepth();
        var projection = clear == 0 ? camera.nativeProjectionMatrix(zeroToOne) : camera.nativeForwardProjectionMatrix(zeroToOne);
        var uniform = uniforms.writeUniform(new Uniform(projection.mul(camera.viewMatrix()).invert(), camera,
                field, clear, zeroToOne));
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = MapFogPlatform.openColor(target, "Earth map highlights")) {
            pass.setPipeline(MapFogPlatform.HIGHLIGHT);
            pass.setUniform("MapHighlightParameters", uniform);
            pass.bindTexture("TerrainDepth", target.getDepthTextureView(), nearest);
            pass.bindTexture("HighlightField", atlas.getTextureView(), nearest);
            MapFogPlatform.draw(pass);
        }
    }

    private void upload(MapHighlightField field) {
        if (atlas == null || field.width() != uploaded.width() || field.height() != uploaded.height()) {
            if (atlas != null) atlas.close();
            atlas = new DynamicTexture(() -> "Earth map highlight field", field.width(), field.height(), false);
        }
        var image = atlas.getPixels();
        for (int i = 0; i < field.pixels().length; i++)
            image.setPixel(i % field.width(), i / field.width(), MapHighlightField.swapRedBlue(field.pixels()[i]));
        atlas.upload();
        uploaded = field;
    }

    @Override public void close() {
        if (uniforms != null) uniforms.close();
        if (atlas != null) atlas.close();
        uniforms = null; atlas = null; uploaded = null;
    }

    private record Uniform(Matrix4f inverse, MapCamera camera, MapHighlightField field, float clear, boolean zeroToOne)
            implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer out) {
            inverse.get(out.position(), out);
            out.position(out.position() + 64);
            out.putFloat(clear).putFloat(zeroToOne ? 1 : 0).putFloat(clear == 0 ? 1 : zeroToOne ? 0 : -1).putFloat(0);
            double chunkX = Math.floor(camera.x() / 16), chunkZ = Math.floor(camera.z() / 16);
            out.putFloat((float) chunkX).putFloat((float) chunkZ);
            out.putFloat(field.mask()).putFloat(field.probes());
            out.putFloat((float) (camera.x() - 16 * chunkX)).putFloat((float) (camera.z() - 16 * chunkZ));
            out.putFloat(0).putFloat(0);
        }
    }
}
