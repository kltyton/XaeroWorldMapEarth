package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.Draw;
import io.github.kltyton.xaeroearth.client.chunkmap.ChunkTileSource;
import io.github.kltyton.xaeroearth.client.chunkmap.MapCamera;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedGrid;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedTileIndex;
import io.github.kltyton.xaeroearth.client.mapmark.MapScope;
import io.github.kltyton.xaeroearth.client.mixin.shaderpack.MapPackTerrainMesh;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.level.block.Block;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.MemoryStack;

/** Submits KUI's map vertices to the shader pack's actual gbuffer or shadow program. */
final class MapPackGeometry implements AutoCloseable {
    private int array;
    private final float[] matrix = new float[16];
    private final Map<Integer, int[]> attributes = new HashMap<>();
    private final Map<Integer, int[]> masks = new HashMap<>();
    private final MapPackDepthMapping.Reader raster = new MapPackDepthMapping.Reader();
    private final GlPackVertexAttributes vertexAttributes = new GlPackVertexAttributes();
    private final Map<Integer, MapPackMaterialTileProgram> materialPrograms = new HashMap<>();
    private final MapPackMaterialTiles materialTiles = new MapPackMaterialTiles();
    private int replayArray;
    private boolean captureFailed;

    record MaterialScene(MapCamera camera, int width, int height, float opticalScale,
                         List<Draw> sources, List<ProjectedTileIndex.Mesh> bounds, MapPackTarget target) { }

    void uploadMaterials(IrisPackAccess iris, IntPredicate waving) throws Throwable {
        var ids = MemoryUtil.memAllocInt(Block.BLOCK_STATE_REGISTRY.size() * 2);
        try {
            iris.materialIds(ids);
            materialTiles.materials(ids, 2, waving);
            vertexAttributes.materials(ids);
        } finally { MemoryUtil.memFree(ids); }
    }

    void beginFrame() { vertexAttributes.beginFrame(); }

    void materialScope(ChunkTileSource source, MapScope scope, String profile) { materialTiles.scope(source, scope, profile); }

    boolean supportsMask(ShaderInstance shader) {
        if (shader == null) return true;
        int[] locations = masks.computeIfAbsent(shader.getId(), MapPackGeometry::maskLocations);
        return locations[3] >= 0 && (locations[4] >= 0
                || locations[0] >= 0 && locations[1] >= 0 && locations[2] >= 0);
    }

    void draw(ShaderInstance shader, Matrix4f camera, Matrix4f view, Matrix4f projection,
              List<Draw> draws, boolean transparent, boolean shadow, boolean partial) {
        draw(shader, camera, view, projection, draws, transparent, shadow, partial, null);
    }

    void draw(ShaderInstance shader, Matrix4f camera, Matrix4f view, Matrix4f projection,
              List<Draw> draws, boolean transparent, boolean shadow, boolean partial, MaterialScene materials) {
        if (shader == null || draws.isEmpty()) return;
        int[] bindings = attributes.computeIfAbsent(shader.getId(), MapPackGeometry::locations);
        if (array == 0) array = GL30.glGenVertexArrays();
        var minecraft = Minecraft.getInstance();
        int atlas = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId();
        RenderSystem.setShaderTexture(0, atlas);
        minecraft.gameRenderer.lightTexture().turnOnLightLayer();
        shader.setSampler("Sampler0", atlas);
        shader.setSampler("Sampler2", RenderSystem.getShaderTexture(2));
        set(shader.PROJECTION_MATRIX, projection);
        // Iris computes its normal and inverse uniforms when applying the shader.
        set(shader.MODEL_VIEW_MATRIX, view);
        if (shader.COLOR_MODULATOR != null) shader.COLOR_MODULATOR.set(1.0f, 1.0f, 1.0f, 1.0f);
        var inWorld = new Matrix4f(camera).invert();
        GlStateManager._glBindVertexArray(array);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(true);
        if (shadow) RenderSystem.disableCull(); else RenderSystem.enableCull();
        try {
            shader.apply();
            if (!shadow) MapPackTarget.captureRaster(raster, shader.getId());
            var program = materialPrograms.computeIfAbsent(shader.getId(), MapPackMaterialTileProgram::new);
            program.bind(shader.getId());
            if (program.supported() && materials != null && !captureFailed)
                drawMaterials(shader, program, inWorld, view, projection, draws, bindings, partial, materials);
            else drawBound(shader, inWorld, view, projection, draws, bindings, transparent, partial);
        } finally {
            shader.clear();
            minecraft.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    private void drawBound(ShaderInstance shader, Matrix4f inWorld, Matrix4f view, Matrix4f projection,
                           List<Draw> draws, int[] bindings, boolean transparent, boolean partial) {
        var tileView = new Matrix4f();
        var tileWorld = new Matrix4f();
        var maskViewport = new MapPackViewport();
        for (Draw draw : draws) {
            var mesh = draw.mesh();
            int size = transparent ? mesh.translucentCount() : mesh.opaqueCount();
            if (size == 0 || draw.chunkMask() != 15 && !partial) continue;
            var buffer = (MapPackTerrainMesh) (Object) mesh;
            int converted = vertexAttributes.get(mesh, buffer.earth$vertices());
            tileWorld.set(inWorld).mul(draw.modelView());
            upload(shader.MODEL_VIEW_MATRIX, tileView.set(view).mul(tileWorld));
            applyMask(shader, projection, tileView, tileWorld, draw.chunkMask(), maskViewport);
            bindVertices(bindings, buffer.earth$vertices(), converted);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, transparent ? buffer.earth$translucentStart() : 0, size);
        }
    }

    private void drawMaterials(ShaderInstance shader, MapPackMaterialTileProgram program, Matrix4f inWorld,
                               Matrix4f view, Matrix4f projection, List<Draw> draws, int[] bindings,
                               boolean partial, MaterialScene scene) {
        MapPackMaterialTiles.Frame frame;
        try {
            frame = materialTiles.prepare(scene.camera(), scene.width(), scene.height(), draws, scene.sources(),
                    scene.bounds(), false, (framebuffer, grid, cell, contributors) -> {
                        var tileProjection = grid.tileProjection(cell).scale(1 / scene.opticalScale());
                        try {
                            upload(shader.PROJECTION_MATRIX, tileProjection);
                            program.capture(shader.getId(), () ->
                                    drawBound(shader, inWorld, view, tileProjection, contributors, bindings, false, partial));
                        } finally {
                            upload(shader.PROJECTION_MATRIX, projection);
                        }
                    });
        } catch (RuntimeException failure) {
            captureFailed = true;
            materialTiles.clear();
            com.mojang.logging.LogUtils.getLogger().warn("Cannot record the current Iris/Oculus material tiles", failure);
            drawBound(shader, inWorld, view, projection, draws, bindings, false, partial);
            return;
        }
        if (frame.pictures().isEmpty()) {
            drawBound(shader, inWorld, view, projection, draws, bindings, false, partial);
            return;
        }
        var viewport = scene.target().raster();
        var inverse = new Matrix4f(projection).mul(view).invert();
        var full = new java.util.ArrayList<Draw>();
        var clipped = new java.util.LinkedHashMap<ProjectedGrid.Cell, List<Draw>>();
        for (Draw draw : draws) {
            var cells = frame.uncovered().get(draw);
            if (cells == null) full.add(draw);
            else for (var cell : cells) clipped.computeIfAbsent(cell, ignored -> new java.util.ArrayList<>()).add(draw);
        }
        boolean wasScissored = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try (var stack = MemoryStack.stackPush()) {
            var previousScissor = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, previousScissor);
            if (replayArray == 0) replayArray = GL30.glGenVertexArrays();
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GlStateManager._glBindVertexArray(replayArray);
            program.mode(2);
            try {
                for (var picture : frame.pictures()) {
                    if (MapPackMaterialTiles.scissor(frame.grid(), picture.cell(), viewport))
                        program.replay(picture.image(), picture.cell(), frame.grid(), viewport, inverse,
                                scene.width(), scene.height(), false, false,
                                () -> GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3));
                }
                program.mode(0);
                GlStateManager._glBindVertexArray(array);
                for (var cell : clipped.entrySet()) {
                if (MapPackMaterialTiles.scissor(frame.grid(), cell.getKey(), viewport))
                        drawBound(shader, inWorld, view, projection, cell.getValue(), bindings, false, partial);
                }
            } finally {
                program.mode(0);
                GL11.glScissor(previousScissor.get(0), previousScissor.get(1), previousScissor.get(2), previousScissor.get(3));
                if (!wasScissored) GL11.glDisable(GL11.GL_SCISSOR_TEST);
                GlStateManager._glBindVertexArray(array);
            }
        }
        drawBound(shader, inWorld, view, projection, full, bindings, false, partial);
    }

    private void set(Uniform uniform, Matrix4f value) {
        if (uniform != null) uniform.set(value.get(matrix));
    }

    private void upload(Uniform uniform, Matrix4f value) {
        if (uniform != null) { set(uniform, value); uniform.upload(); }
    }

    private void applyMask(ShaderInstance shader, Matrix4f projection, Matrix4f modelView, Matrix4f world,
                           int mask, MapPackViewport viewport) {
        int[] uniform = masks.computeIfAbsent(shader.getId(), MapPackGeometry::maskLocations);
        if (uniform[3] < 0) return;
        GL20.glUniform1i(uniform[3], mask);
        if (mask == 15) return;
        if (uniform[4] >= 0) {
            GL20.glUniform2f(uniform[4], world.m30(), world.m32());
            return;
        }
        if (uniform[0] < 0 || uniform[1] < 0 || uniform[2] < 0) return;
        viewport.capture();
        GL20.glUniformMatrix4fv(uniform[0], false, new Matrix4f(projection).mul(modelView).invert().get(matrix));
        GL20.glUniform4f(uniform[1], viewport.viewport[0], viewport.viewport[1], viewport.viewport[2], viewport.viewport[3]);
        GL20.glUniform2f(uniform[2], (float) viewport.depth[0], (float) viewport.depth[1]);
    }

    private static int[] maskLocations(int program) {
        return new int[]{GL20.glGetUniformLocation(program, "EarthMaskInverse"),
                GL20.glGetUniformLocation(program, "EarthMaskViewport"),
                GL20.glGetUniformLocation(program, "EarthMaskDepthRange"),
                GL20.glGetUniformLocation(program, "EarthMaskBits"),
                GL20.glGetUniformLocation(program, "EarthMaskOrigin")};
    }

    private static void bindVertices(int[] locations, int buffer, int converted) {
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        attribute(locations[0], 3, GL11.GL_FLOAT, false, false, 32, 0);
        attribute(locations[2], 2, GL11.GL_FLOAT, false, false, 32, 16);
        attribute(locations[4], 3, GL11.GL_BYTE, true, false, 32, 28);
        GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, converted);
        int stride = GlPackVertexAttributes.STRIDE;
        attribute(locations[3], 2, GL11.GL_SHORT, false, true, stride, 0);
        attribute(locations[1], 4, GL11.GL_UNSIGNED_BYTE, true, false, stride, 4);
        attribute(locations[6], 2, GL11.GL_FLOAT, false, false, stride, 8);
        attribute(locations[7], 4, GL11.GL_BYTE, true, false, stride, 16);
        attribute(locations[8], 4, GL11.GL_BYTE, false, false, stride, 20);
        attribute(locations[5], 2, GL11.GL_FLOAT, false, false, stride, 24);
    }

    private static int[] locations(int program) {
        return new int[]{location(program, "Position"), location(program, "Color"), location(program, "UV0"),
                location(program, "UV2"), location(program, "Normal"), location(program, "mc_Entity"),
                location(program, "mc_midTexCoord"), location(program, "at_tangent"), location(program, "at_midBlock")};
    }

    private static int location(int program, String name) {
        int loc = GL20.glGetAttribLocation(program, name);
        return loc >= 0 ? loc : GL20.glGetAttribLocation(program, "iris_" + name);
    }

    private static void attribute(int loc, int count, int type, boolean normalized, boolean integer, int stride, long offset) {
        if (loc < 0) return;
        GL20.glEnableVertexAttribArray(loc);
        if (integer) GL30.glVertexAttribIPointer(loc, count, type, stride, offset);
        else GL20.glVertexAttribPointer(loc, count, type, normalized, stride, offset);
    }

    void resetPrograms() {
        captureFailed = false;
        vertexAttributes.clearMeshes();
        attributes.clear();
        masks.clear();
        raster.clear();
        materialPrograms.clear();
        materialTiles.clear();
    }

    @Override public void close() {
        vertexAttributes.close();
        materialTiles.close();
        if (array != 0) GL30.glDeleteVertexArrays(array);
        if (replayArray != 0) GL30.glDeleteVertexArrays(replayArray);
        array = 0;
        replayArray = 0;
    }
}
