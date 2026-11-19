package io.github.kltyton.xaeroearth.client.render.shaderpack;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL33;

/** Texture and sampler bindings which the pack's gbuffer and composite stages change. */
final class PackGlState implements AutoCloseable {
    private final int active = GlStateManager._getActiveTexture();
    private final int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
    private final int[] minecraftTextures = new int[12];
    private final int[] textures;
    private final int[] samplers;

    PackGlState() {
        int units = Math.min(32, GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
        textures = new int[units];
        samplers = new int[units];
        for (int i = 0; i < minecraftTextures.length; i++) minecraftTextures[i] = RenderSystem.getShaderTexture(i);
        for (int unit = 0; unit < units; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            samplers[unit] = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        }
        GL13.glActiveTexture(active);
    }

    @Override public void close() {
        for (int unit = 0; unit < textures.length; unit++) {
            // Iris extends GlStateManager's tracked unit table. Restore through it
            // so the cached bindings match the physical bindings in the next pass.
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            GlStateManager._bindTexture(textures[unit]);
            GL33.glBindSampler(unit, samplers[unit]);
        }
        GL13.glActiveTexture(active);
        GlStateManager._activeTexture(active);
        GlStateManager._glUseProgram(program);
        for (int i = 0; i < minecraftTextures.length; i++) RenderSystem.setShaderTexture(i, minecraftTextures[i]);
    }
}
