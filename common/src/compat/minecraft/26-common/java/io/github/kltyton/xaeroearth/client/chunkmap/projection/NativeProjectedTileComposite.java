package io.github.kltyton.xaeroearth.client.chunkmap.projection;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;

/** Composites device-rendered tiles while retaining their per-pixel terrain depth. */
public final class NativeProjectedTileComposite implements AutoCloseable {
    private static final RenderPipeline PIPELINE = NativeProjectedDevice.compositePipeline();
    private final DynamicUniformStorage<Parameters> parameters =
            new DynamicUniformStorage<>("Earth projected tile parameters", 48, 256);

    public record Draw(ProjectedGrid.Cell cell, NativeProjectedTileBaker.Tile image,
                       double cameraX, double cameraY, double cameraZ) { }

    private record Prepared(NativeProjectedTileBaker.Tile image, ProjectedDrawCoverage.Clip clip, GpuBufferSlice uniform) { }

    public void beginFrame() { parameters.endFrame(); }

    public void draw(RenderTarget target, ProjectedGrid grid, float sky, List<Draw> draws) {
        draw(target, grid, sky, draws, false);
    }

    void seed(RenderTarget target, ProjectedGrid grid, float sky, Draw draw) {
        draw(target, grid, sky, List.of(draw), true);
    }

    private void draw(RenderTarget target, ProjectedGrid grid, float sky, List<Draw> draws, boolean entireImage) {
        if (!Minecraft.getInstance().isSameThread())
            throw new IllegalStateException("Projected tile drawing requires the client thread");
        if (draws.isEmpty()) return;
        var prepared = new ArrayList<Prepared>(draws.size());
        // Mapping the device's uniform buffers must finish before starting the render pass.
        for (Draw draw : draws) {
            var clip = entireImage ? null : grid.clip(draw.cell());
            if (!entireImage && clip == null) continue;
            float x = entireImage ? 0 : (float) grid.cellScreenX(draw.cell().x());
            float y = entireImage ? 0 : (float) grid.cellScreenY(draw.cell().y());
            var values = parameters.writeUniform(new Parameters(x, y, target.width, target.height, sky,
                    (float) grid.depthOffsetFrom(draw.cameraX(), draw.cameraY(), draw.cameraZ(), NativeProjectedDevice.reverseDepth()),
                    entireImage ? ProjectedGrid.IMAGE_PIXELS : ProjectedGrid.TILE_PIXELS,
                    entireImage ? 0 : ProjectedGrid.GUTTER));
            prepared.add(new Prepared(draw.image(), clip, values));
        }
        if (prepared.isEmpty()) return;
        var linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = NativeProjectedDevice.openComposite(target)) {
            pass.setPipeline(PIPELINE);
            for (var draw : prepared) {
                var clip = draw.clip();
                if (clip != null) pass.enableScissor(clip.x(), clip.y(), clip.width(), clip.height());
                pass.setUniform("ProjectedTileParams", draw.uniform());
                pass.bindTexture("LowColor", draw.image().low().getColorTextureView(), linear);
                pass.bindTexture("HighColor", draw.image().high().getColorTextureView(), linear);
                pass.bindTexture("EncodedDepth", draw.image().encodedDepth().getColorTextureView(), nearest);
                NativeProjectedDevice.draw(pass);
            }
        }
    }

    private record Parameters(float x, float y, float width, float height, float sky, float depthShift,
                              float tilePixels, float gutter)
            implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer output) {
            output.putFloat(x).putFloat(y).putFloat(width).putFloat(height);
            output.putFloat(sky).putFloat(depthShift).putFloat(tilePixels).putFloat(gutter);
            output.putFloat(NativeProjectedDevice.reverseDepth() ? 1 : 0).putFloat(0).putFloat(0).putFloat(0);
        }
    }

    @Override public void close() { parameters.close(); }
}
