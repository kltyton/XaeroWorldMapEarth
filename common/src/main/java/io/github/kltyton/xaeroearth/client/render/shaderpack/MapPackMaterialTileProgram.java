package io.github.kltyton.xaeroearth.client.render.shaderpack;

import io.github.kltyton.xaeroearth.client.chunkmap.projection.ProjectedGrid;
import java.util.BitSet;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.system.MemoryStack;

/** Controls the isolated pack program while recording and replaying a material tile. */
final class MapPackMaterialTileProgram {
    private final int mode, rectangle, viewport, adjust, unproject, planes, depth, captureScale;
    private int firstUnit = -1, secondUnit = -1;

    MapPackMaterialTileProgram(int program) {
        mode = GL20.glGetUniformLocation(program, "EarthMaterialTileMode");
        rectangle = location(program, "EarthMaterialTileRect");
        viewport = location(program, "EarthMaterialViewport");
        adjust = location(program, "EarthMaterialAdjust");
        unproject = location(program, "EarthMaterialUnproject");
        planes = location(program, "EarthMaterialPlanes");
        depth = location(program, "EarthMaterialDepth");
        captureScale = location(program, "fsrRenderScale");
    }

    private int location(int program, String name) {
        return mode < 0 ? -1 : GL20.glGetUniformLocation(program, name);
    }

    boolean supported() {
        return mode >= 0 && rectangle >= 0 && viewport >= 0 && adjust >= 0
                && unproject >= 0 && planes >= 0 && depth >= 0;
    }

    void bind(int program) {
        if (!supported()) return;
        if (firstUnit < 0) reserveUnits(program);
        GL20.glUniform1i(planes, firstUnit);
        GL20.glUniform1i(depth, secondUnit);
        mode(0);
    }

    void mode(int value) { GL20.glUniform1i(mode, value); }

    void capture(int program, Runnable draw) {
        try (var stack = MemoryStack.stackPush()) {
            var previous = stack.mallocFloat(2);
            if (captureScale >= 0) {
                GL20.glGetUniformfv(program, captureScale, previous);
                // The pack already compensates its atlas derivatives for FSR. Captures run at full pixel density.
                GL20.glUniform2f(captureScale, 1, 1);
            }
            mode(1);
            try { MapPackMaterialTileTextures.materialWrites(draw); }
            finally {
                mode(0);
                if (captureScale >= 0) GL20.glUniform2f(captureScale, previous.get(0), previous.get(1));
            }
        }
    }

    void replay(MapPackMaterialTileTextures.Tile image, ProjectedGrid.Cell cell, ProjectedGrid grid,
                MapPackDepthMapping raster, Matrix4f inverse, int width, int height,
                boolean reverseDepth, boolean zeroToOne, Runnable draw) {
        float size = ProjectedGrid.TILE_PIXELS;
        float left = (float) grid.cellScreenX(cell.x()), bottom = (float) (height - grid.cellScreenY(cell.y()) - size);
        float shift = (float) grid.depthOffsetFrom(image.x(), image.y(), image.z(), reverseDepth);
        GL20.glUniform4f(rectangle, left, bottom, size, size);
        GL20.glUniform4f(viewport, width, height, raster.xScale(), raster.yScale());
        GL20.glUniform4f(adjust, raster.xOffset(), raster.yOffset(), shift, zeroToOne ? 1 : 2);
        try (var stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(unproject, false, inverse.get(stack.mallocFloat(16)));
        }
        MapPackMaterialTileTextures.sample(image, firstUnit, secondUnit, draw);
    }

    private void reserveUnits(int program) {
        int units = GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        var taken = new BitSet(units);
        try (var stack = MemoryStack.stackPush()) {
            var size = stack.mallocInt(1);
            var type = stack.mallocInt(1);
            int uniforms = GL20.glGetProgrami(program, GL20.GL_ACTIVE_UNIFORMS);
            for (int i = 0; i < uniforms; i++) {
                String name = GL20.glGetActiveUniform(program, i, size, type);
                if (!sampler(type.get(0)) || name.startsWith("EarthMaterial")) continue;
                for (int element = 0; element < size.get(0); element++) {
                    String indexed = size.get(0) == 1 ? name : name.replace("[0]", "[" + element + "]");
                    int location = GL20.glGetUniformLocation(program, indexed);
                    if (location >= 0) taken.set(GL20.glGetUniformi(program, location));
                }
            }
        }
        for (int i = units - 1; i >= 0; i--) {
            if (taken.get(i)) continue;
            if (firstUnit < 0) firstUnit = i;
            else { secondUnit = i; return; }
        }
        throw new IllegalStateException("Shader pack has no independent material tile sampler units");
    }

    private static boolean sampler(int type) {
        // GLSL float, integer, array, rectangle, cubemap-array and multisample sampler enums.
        return type >= 0x8b5d && type <= 0x8b64 || type >= 0x8dc0 && type <= 0x8dd8
                || type >= 0x900c && type <= 0x900f || type >= 0x9108 && type <= 0x910d;
    }
}
