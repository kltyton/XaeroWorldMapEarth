package io.github.kltyton.xaeroearth.client.chunkmap;

import io.github.kltyton.kltytonui.chunkmap.ChunkMapSnapshot;
import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/** Block-resolved surface columns rendered from bounded native snapshots and the current block atlas. */
public final class BlockSurfaceRenderer implements AutoCloseable, BlockSurfaceData {
    private static final RenderPipeline OPAQUE = pipeline(false), TRANSPARENT = pipeline(true);
    private final ProjectionMatrixBuffer projection = new ProjectionMatrixBuffer("KUI block surface projection");
    private final DynamicUniformStorage<Parameters> parameters = new DynamicUniformStorage<>("KUI block surface parameters", 64, 512);
    private final List<GpuTile> uploaded = new ArrayList<>();
    private Surface surface;
    private GpuTexture palette;
    private GpuTextureView paletteView;
    private ByteBuffer transfer;
    private long columns;
    private Statistics statistics = new Statistics(-1, 0, 0, 0, 0, 1);

    private static RenderPipeline pipeline(boolean transparent) {
        return RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
                .withUniform("BlockSurfaceParams", UniformType.UNIFORM_BUFFER).withSampler("ColumnHeight")
                .withSampler("ColumnRanges").withSampler("ColumnRuns").withSampler("Materials").withSampler("Sampler0")
                .withLocation(Identifier.fromNamespaceAndPath("xaeroearth", transparent ? "native_block_surface_transparent" : "native_block_surface"))
                .withVertexShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/native_block_surface"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("xaeroearth", "core/native_block_surface"))
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES).withCull(false)
                .withDepthStencilState(DepthStencilState.DEFAULT)
                .withColorTargetState(new ColorTargetState(transparent ? Optional.of(com.mojang.blaze3d.pipeline.BlendFunction.TRANSLUCENT)
                        : Optional.empty(), 15)).build();
    }

    public Statistics statistics() { return statistics; }

    /** The immutable surface may be reused across camera changes; uploads are limited to one region per frame. */
    public void update(Surface next) {
        requireRenderThread();
        if (next != surface) {
            reset();
            surface = next;
            if (next != null) {
                int rows = next.palette().length / (PALETTE_WIDTH * 4);
                palette = texture("KUI block surface materials", PALETTE_WIDTH * 4, rows);
                paletteView = RenderSystem.getDevice().createTextureView(palette);
                floats(palette, next.palette());
            }
        }
        if (next == null || uploaded.size() == next.tiles().size()) return;
        Tile tile = next.tiles().get(uploaded.size());
        int width = tile.width() + HALO * 2, depth = tile.depth() + HALO * 2;
        GpuTexture heights = texture("KUI block column heights", width * 4, depth);
        GpuTexture ranges = texture("KUI block column ranges", width * 2, depth);
        int rows = Math.max(1, Math.ceilDiv(tile.runs().length / 4, RUN_WIDTH));
        GpuTexture runs = texture("KUI block column runs", RUN_WIDTH * 4, rows);
        floats(heights, tile.heights());
        integers(ranges, tile.ranges(), tile.ranges().length);
        integers(runs, tile.runs(), RUN_WIDTH * rows * 4);
        uploaded.add(new GpuTile(tile, heights, ranges, runs));
        columns += (long) tile.width() * tile.depth();
        statistics = new Statistics(next.revision(), uploaded.size(), next.tiles().size(), columns, 0, 1);
    }

    public boolean draw(MapCamera camera, RenderTarget target, float brightness) {
        requireRenderThread();
        if (surface == null || uploaded.size() != surface.tiles().size()) return false;
        double pixelsPerBlock = camera.pixelsPerBlock() * target.width / camera.width();
        int step = Math.clamp(Integer.highestOneBit(Math.max(1, (int) (1.0 / pixelsPerBlock))), 1, 4);
        var frustum = new FrustumIntersection(new Matrix4f(camera.projectionMatrix()).mul(camera.viewMatrix()));
        List<GpuTile> visible = uploaded.stream().filter(tile -> frustum.testAab(
                (float) (tile.data.x() - camera.x()), tile.data.minY() - (float) camera.y(), (float) (tile.data.z() - camera.z()),
                (float) (tile.data.x() + tile.data.width() - camera.x()), tile.data.maxY() - (float) camera.y(),
                (float) (tile.data.z() + tile.data.depth() - camera.z()))).toList();
        if (visible.isEmpty()) return false;
        parameters.endFrame();
        var transform = RenderSystem.getDynamicUniforms().writeTransform(new Matrix4f(camera.viewMatrix()),
                new Vector4f(1, 1, 1, 1), new Vector3f(), new Matrix4f());
        var oldProjection = RenderSystem.getProjectionMatrixBuffer();
        var oldType = RenderSystem.getProjectionType();
        RenderSystem.setProjectionMatrix(projection.getBuffer(camera.nativeProjectionMatrix(RenderSystem.getDevice().isZZeroToOne())),
                camera.mode().equals("street") ? ProjectionType.PERSPECTIVE : ProjectionType.ORTHOGRAPHIC);
        var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        try {
            for (int layer : new int[]{0, 2, 1}) {
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "KUI block resolved surface",
                        target.getColorTextureView(), layer == 0 ? OptionalInt.of(0xFF75ABD1) : OptionalInt.empty(),
                        target.getDepthTextureView(), layer == 0 ? OptionalDouble.of(1) : OptionalDouble.empty())) {
                    pass.setPipeline(layer == 0 ? OPAQUE : TRANSPARENT);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", transform);
                    pass.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
                    var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
                    pass.bindTexture("Materials", paletteView, nearest);
                    for (GpuTile tile : visible) {
                        var values = parameters.writeUniform(new Parameters(tile.data.width(), tile.data.depth(), step, layer,
                                (float) (tile.data.x() - camera.x()), (float) -camera.y(), (float) (tile.data.z() - camera.z()), brightness,
                                Math.sin(camera.yaw()) > 0 ? -1 : 1, Math.cos(camera.yaw()) > 0 ? 1 : -1));
                        pass.setUniform("BlockSurfaceParams", values);
                        pass.bindTexture("ColumnHeight", tile.heightView, nearest);
                        pass.bindTexture("ColumnRanges", tile.rangeView, nearest);
                        pass.bindTexture("ColumnRuns", tile.runView, nearest);
                        int vertices = layer == 0 ? 24 : layer == 2 ? 6 : 18;
                        pass.draw(0, Math.multiplyExact(Math.ceilDiv(tile.data.width(), step) * Math.ceilDiv(tile.data.depth(), step), vertices));
                    }
                }
            }
        } finally { RenderSystem.setProjectionMatrix(oldProjection, oldType); }
        statistics = new Statistics(surface.revision(), uploaded.size(), surface.tiles().size(), columns, visible.size(), step);
        return true;
    }

    private GpuTexture texture(String name, int width, int height) {
        return RenderSystem.getDevice().createTexture(() -> name, GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                TextureFormat.RGBA8, width, height, 1, 1);
    }
    private void floats(GpuTexture texture, float[] values) {
        prepare(values.length * 4);
        for (float value : values) transfer.putFloat(value);
        upload(texture);
    }
    private void integers(GpuTexture texture, int[] values, int length) {
        prepare(length * 4);
        for (int value : values) transfer.putInt(value);
        while (transfer.position() < length * 4) transfer.putInt(0);
        upload(texture);
    }
    private void prepare(int bytes) {
        if (transfer == null || transfer.capacity() < bytes) {
            if (transfer != null) MemoryUtil.memFree(transfer);
            transfer = MemoryUtil.memAlloc(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        transfer.clear().limit(bytes);
    }
    private void upload(GpuTexture texture) {
        transfer.flip();
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.writeToTexture(texture, transfer, NativeImage.Format.RGBA,
                0, 0, 0, 0, texture.getWidth(0), texture.getHeight(0));
    }
    public void reset() {
        requireRenderThread();
        uploaded.forEach(GpuTile::close); uploaded.clear(); columns = 0; surface = null;
        if (paletteView != null) { paletteView.close(); paletteView = null; }
        if (palette != null) { palette.close(); palette = null; }
        statistics = new Statistics(-1, 0, 0, 0, 0, 1);
    }
    @Override public void close() {
        reset(); projection.close(); parameters.close();
        if (transfer != null) { MemoryUtil.memFree(transfer); transfer = null; }
    }
    private static void requireRenderThread() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Block surface GPU work requires the client thread");
    }
    private static final class GpuTile implements AutoCloseable {
        final Tile data; final GpuTexture height, range, run; final GpuTextureView heightView, rangeView, runView;
        GpuTile(Tile data, GpuTexture height, GpuTexture range, GpuTexture run) {
            this.data = data; this.height = height; this.range = range; this.run = run;
            var device = RenderSystem.getDevice(); heightView = device.createTextureView(height);
            rangeView = device.createTextureView(range); runView = device.createTextureView(run);
        }
        @Override public void close() { heightView.close(); rangeView.close(); runView.close(); height.close(); range.close(); run.close(); }
    }
    private record Parameters(float width, float depth, float step, float layer, float x, float y, float z, float brightness,
                              float directionX, float directionZ) implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer buffer) {
            buffer.putFloat(width).putFloat(depth).putFloat(step).putFloat(layer);
            buffer.putFloat(x).putFloat(y).putFloat(z).putFloat(brightness);
            buffer.putFloat(directionX).putFloat(directionZ).putFloat(0).putFloat(0);
            buffer.putFloat(RUN_WIDTH).putFloat(0).putFloat(0).putFloat(0);
        }
    }

    /** Captures immutable model resources on the client thread; encoding then uses detached snapshots only. */
    public static final class Capture extends BlockSurfaceModels { }
}
