package io.github.kltyton.xaeroearth.client.render.shaderpack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL43;

/** Gives each Iris pipeline its own indexed OpenGL shader storage bindings. */
final class MapPackStorageBindings implements AutoCloseable {
    private final int[] indices;
    private final int[] buffers, previousBuffers;
    private final long[] previousStarts, previousSizes;
    private int previousGeneric;
    private boolean initialized;

    MapPackStorageBindings(int[] indices) {
        this.indices = indices;
        buffers = new int[indices.length];
        previousBuffers = new int[indices.length];
        previousStarts = new long[indices.length];
        previousSizes = new long[indices.length];
    }

    MapPackStorageBindings preserve() {
        previousGeneric = GL11.glGetInteger(GL43.GL_SHADER_STORAGE_BUFFER_BINDING);
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            previousBuffers[i] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, index);
            if (previousBuffers[i] != 0) {
                previousStarts[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_START, index);
                previousSizes[i] = GL32.glGetInteger64i(GL43.GL_SHADER_STORAGE_BUFFER_SIZE, index);
            }
        }
        return this;
    }

    MapPackStorageBindings enter() {
        preserve();
        if (initialized) for (int i = 0; i < indices.length; i++)
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, indices[i], buffers[i]);
        return this;
    }

    /** Iris creates the indexed bindings in its constructor and can replace buffers on resize. */
    void remember() {
        for (int i = 0; i < indices.length; i++) {
            buffers[i] = GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, indices[i]);
        }
        initialized = true;
    }

    @Override public void close() {
        for (int i = 0; i < indices.length; i++) {
            if (previousBuffers[i] != 0 && previousSizes[i] > 0) {
                GL30.glBindBufferRange(GL43.GL_SHADER_STORAGE_BUFFER, indices[i], previousBuffers[i],
                        previousStarts[i], previousSizes[i]);
            } else {
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, indices[i], previousBuffers[i]);
            }
        }
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, previousGeneric);
    }
}
