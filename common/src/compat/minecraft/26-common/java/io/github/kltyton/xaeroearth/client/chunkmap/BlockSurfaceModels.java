package io.github.kltyton.xaeroearth.client.chunkmap;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Adapts the native block/fluid model sets to detached column materials. */
class BlockSurfaceModels extends BlockSurfaceCapture<BlockStateModelPart> {
    private final BlockStateModelSet blocks;
    private final FluidStateModelSet fluids;
    private final ThreadLocal<Encoding> encoding = ThreadLocal.withInitial(Encoding::new);

    BlockSurfaceModels() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Block surface GPU work requires the client thread");
        var models = Minecraft.getInstance().getModelManager();
        blocks = models.getBlockStateModelSet();
        fluids = models.getFluidStateModelSet();
    }

    public boolean resourcesCurrent() { return blocks == Minecraft.getInstance().getModelManager().getBlockStateModelSet(); }

    @Override protected List<BlockStateModelPart> parts(BlockState state, int x, int y, int z) {
        Encoding local = encoding.get();
        local.random.setSeed(state.getSeed(local.pos.set(x, y, z)));
        local.parts.clear();
        if (state.getFluidState().isEmpty()) blocks.get(state).collectParts(local.random, local.parts);
        return List.copyOf(local.parts);
    }

    @Override protected boolean translucent(BlockState state) {
        return blocks.get(state).hasMaterialFlag(BakedQuad.FLAG_TRANSLUCENT);
    }

    @Override protected Material material(BlockState state, List<BlockStateModelPart> parts, int color) {
        float[] data = new float[PALETTE_WIDTH * 4]; float height = 1; boolean transparent = false;
        if (!state.getFluidState().isEmpty()) {
            var model = fluids.get(state.getFluidState());
            for (int face = 0; face < FACES.length; face++) {
                sprite(data, face * 24, face == 0 ? model.stillMaterial().sprite() : model.flowingMaterial().sprite(), color, 0.75f);
                if (face != 0) { data[face * 24 + 2] *= 0.5f; data[face * 24 + 1] += data[face * 24 + 5] * 0.5f; data[face * 24 + 5] *= -0.5f; }
            }
            data[10] = 8f / 9f;
            return new Material(data, 8f / 9f, true);
        }
        for (int face = 0; face < FACES.length; face++) {
            List<BakedQuad> quads = new ArrayList<>();
            for (var part : parts) { quads.addAll(part.getQuads(FACES[face])); for (var quad : part.getQuads(null)) if (quad.direction() == FACES[face]) quads.add(quad); }
            if (quads.isEmpty() || quads.size() > 2) throw new IllegalArgumentException("Unsupported terrain column face " + state + " " + FACES[face] + " quads=" + quads.size());
            for (int q = 0; q < quads.size(); q++) {
                var quad = quads.get(q); face(data, face * 24 + q * 12, quad, color);
                transparent |= quad.materialInfo().layer().translucent();
                if (face == 0) for (int v = 0; v < 4; v++) height = q == 0 && v == 0 ? quad.position(v).y() : Math.max(height, quad.position(v).y());
            }
        }
        data[10] = height;
        return new Material(data, height, transparent);
    }
    private static void face(float[] output, int at, BakedQuad quad, int tint) {
        int[] corners = new int[3]; float[] best = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        for (int i = 0; i < 4; i++) {
            var p = quad.position(i); boolean top = quad.direction() == Direction.UP;
            float a = top || quad.direction().getAxis() == Direction.Axis.Z ? p.x() : p.z(); float b = top ? p.z() : p.y();
            for (int c = 0; c < 3; c++) { float d = Math.abs(a - (c == 1 ? 1 : 0)) + Math.abs(b - (c == 2 ? 1 : 0)); if (d < best[c]) { best[c] = d; corners[c] = i; } }
        }
        float u = UVPair.unpackU(quad.packedUV(corners[0])), v = UVPair.unpackV(quad.packedUV(corners[0]));
        output[at] = u; output[at + 1] = v;
        output[at + 2] = UVPair.unpackU(quad.packedUV(corners[1])) - u; output[at + 3] = UVPair.unpackV(quad.packedUV(corners[1])) - v;
        output[at + 4] = UVPair.unpackU(quad.packedUV(corners[2])) - u; output[at + 5] = UVPair.unpackV(quad.packedUV(corners[2])) - v;
        boolean top = quad.direction() == Direction.UP;
        var a = quad.position(corners[0]); var b = quad.position(corners[1]); var c = quad.position(corners[2]);
        float width = top || quad.direction().getAxis() == Direction.Axis.Z ? b.x() - a.x() : b.z() - a.z();
        float height = top ? c.z() - a.z() : c.y() - a.y();
        output[at + 2] /= width; output[at + 3] /= width; output[at + 4] /= height; output[at + 5] /= height;
        color(output, at + 6, quad.materialInfo().isTinted() ? tint : 0xffffff, 1);
    }
    private static void sprite(float[] output, int at, TextureAtlasSprite sprite, int tint, float alpha) {
        output[at] = sprite.getU0(); output[at + 1] = sprite.getV0(); output[at + 2] = sprite.getU1() - sprite.getU0();
        output[at + 5] = sprite.getV1() - sprite.getV0(); color(output, at + 6, tint, alpha);
    }
    private static void color(float[] output, int at, int color, float alpha) {
        output[at] = (color >> 16 & 255) / 255f; output[at + 1] = (color >> 8 & 255) / 255f; output[at + 2] = (color & 255) / 255f; output[at + 3] = alpha;
    }
    private static final class Encoding { final RandomSource random = RandomSource.create(0); final List<BlockStateModelPart> parts = new ArrayList<>(); final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(); }
}
