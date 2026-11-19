package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.kltytonui.chunkmap.KuiNativeTerrainRenderer.Draw;
import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedGrid;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/** Runs only after the native device has bound the Iris program and render state for a map draw. */
final class NativeMapMaterialPass {
    private final Map<Integer, MapPackMaterialTileProgram> programs;
    private final MapPackDepthMapping.Reader rasterReader;
    private final MapPackMaterialTiles.Frame frame;
    private final Matrix4f inverse;
    private final int width, height, framebuffer, replayArray;
    private final boolean reverseDepth, zeroToOne;
    private final Map<Draw, Draw> sources = new IdentityHashMap<>();
    private Draw draw;
    private boolean submitted, replayed;
    private int programId;

    NativeMapMaterialPass(Map<Integer, MapPackMaterialTileProgram> programs, MapPackDepthMapping.Reader rasterReader,
                          MapPackMaterialTiles.Frame frame, Matrix4f inverse, int width, int height,
                          int framebuffer, int replayArray, boolean reverseDepth, boolean zeroToOne) {
        this.programs = programs;
        this.rasterReader = rasterReader;
        this.frame = frame;
        this.inverse = inverse;
        this.width = width;
        this.height = height;
        this.framebuffer = framebuffer;
        this.replayArray = replayArray;
        this.reverseDepth = reverseDepth;
        this.zeroToOne = zeroToOne;
    }

    void sources(List<Draw> actual, List<Draw> original) {
        if (frame != null && actual != original) {
            for (int i = 0; i < actual.size(); i++) sources.put(actual.get(i), original.get(i));
        }
    }

    void draw(Draw draw) { this.draw = sources.getOrDefault(draw, draw); }
    boolean submitted() { return submitted; }
    int programId() { return programId; }

    void adjustViewport(MapPackViewport viewport) {
        if (framebuffer == 0) return;
        viewport.viewport[0] = viewport.viewport[1] = 0;
        viewport.viewport[2] = viewport.viewport[3] = ProjectedGrid.IMAGE_PIXELS;
    }

    void submit(Runnable original) {
        boolean first = programId == 0;
        if (first) programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        if (programId == 0) { original.run(); return; }
        var program = programs.computeIfAbsent(programId, MapPackMaterialTileProgram::new);
        if (first) program.bind(programId);
        if (!program.supported()) {
            if (framebuffer == 0) original.run();
            return;
        }
        if (framebuffer != 0) {
            capture(program, original);
            submitted = true;
        } else {
            MapPackTarget.captureRaster(rasterReader, programId);
            if (frame == null || frame.pictures().isEmpty()) original.run();
            else project(program, MapPackTarget.activeRaster(), original);
        }
    }

    private void capture(MapPackMaterialTileProgram program, Runnable original) {
        int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try (var stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, ProjectedGrid.IMAGE_PIXELS, ProjectedGrid.IMAGE_PIXELS);
            try {
                program.capture(programId, original);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
                GL11.glViewport(viewport.get(0), viewport.get(1), viewport.get(2), viewport.get(3));
            }
        }
    }

    private void project(MapPackMaterialTileProgram program, MapPackDepthMapping raster, Runnable original) {
        var uncovered = frame.uncovered().get(draw);
        if (replayed && uncovered == null) {
            original.run();
            return;
        }
        if (replayed && uncovered.isEmpty()) return;
        try (var stack = MemoryStack.stackPush()) {
            var scissor = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, scissor);
            boolean enabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (!enabled) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            try {
                if (!replayed) {
                    int previous = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
                    GL30.glBindVertexArray(replayArray);
                    program.mode(2);
                    try {
                        for (var picture : frame.pictures()) {
                            if (MapPackMaterialTiles.scissor(frame.grid(), picture.cell(), raster))
                                program.replay(picture.image(), picture.cell(), frame.grid(), raster, inverse, width, height,
                                        reverseDepth, zeroToOne, () -> GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3));
                        }
                        replayed = true;
                    } finally {
                        program.mode(0);
                        GL30.glBindVertexArray(previous);
                    }
                }
                if (uncovered != null) for (var cell : uncovered) {
                    if (MapPackMaterialTiles.scissor(frame.grid(), cell, raster)) original.run();
                }
            } finally {
                GL11.glScissor(scissor.get(0), scissor.get(1), scissor.get(2), scissor.get(3));
                if (!enabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
        if (uncovered == null) original.run();
    }
}
