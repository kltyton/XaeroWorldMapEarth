package io.github.kltyton.xaeroearth.client.render.shaderpack;

import java.util.HashMap;
import java.util.Map;
import org.lwjgl.opengl.EXTShaderImageLoadStore;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42C;

/** Restores the world's image units after a map pack dispatches its own image stores. */
final class MapPackImageBindings {
    private record Binding(int texture, int level, boolean layered, int layer, int access, int format) {
        static Binding capture(int unit) {
            int texture = GL30.glGetIntegeri(GL42C.GL_IMAGE_BINDING_NAME, unit);
            if (texture == 0) return new Binding(0, 0, false, 0, GL15.GL_READ_ONLY, GL30.GL_R8);
            return new Binding(texture, GL30.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LEVEL, unit),
                    GL30.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYERED, unit) != GL11.GL_FALSE,
                    GL30.glGetIntegeri(GL42C.GL_IMAGE_BINDING_LAYER, unit),
                    GL30.glGetIntegeri(GL42C.GL_IMAGE_BINDING_ACCESS, unit),
                    GL30.glGetIntegeri(GL42C.GL_IMAGE_BINDING_FORMAT, unit));
        }

        void restore(int unit, boolean core) {
            if (core) GL42C.glBindImageTexture(unit, texture, level, layered, layer, access, format);
            else EXTShaderImageLoadStore.glBindImageTextureEXT(unit, texture, level, layered, layer, access, format);
        }
    }

    private final Map<Integer, Binding> previous = new HashMap<>();

    void remember(int unit) {
        previous.computeIfAbsent(unit, Binding::capture);
    }

    void restore() {
        if (previous.isEmpty()) return;
        var gl = GL.getCapabilities();
        boolean core = gl.OpenGL42 || gl.GL_ARB_shader_image_load_store;
        previous.forEach((unit, binding) -> binding.restore(unit, core));
        previous.clear();
    }
}
