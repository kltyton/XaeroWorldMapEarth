package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.mojang.blaze3d.opengl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the 26.1 OpenGL name for writing the Iris copy through transform feedback. */
@Mixin(GlBuffer.class)
public interface NativePackGlBufferAccess {
    @Accessor("handle")
    int earth$glHandle();
}
