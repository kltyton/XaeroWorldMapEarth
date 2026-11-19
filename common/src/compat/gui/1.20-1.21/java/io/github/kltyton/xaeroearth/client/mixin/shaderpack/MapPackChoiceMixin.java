package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import io.github.kltyton.xaeroearth.client.EarthPreferences;
import io.github.kltyton.xaeroearth.client.render.shaderpack.GlMapShaderPackRenderer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Separate opt-in for the map in the loader's shader pack chooser. */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.gui.screen.ShaderPackScreen", "net.coderbot.iris.gui.screen.ShaderPackScreen"},
        remap = false)
abstract class MapPackChoiceMixin extends Screen {
    protected MapPackChoiceMixin(Component title) { super(title); }

    @Inject(method = {"init()V", "m_7856_()V", "method_25426()V"}, at = @At("TAIL"), require = 0)
    private void earth$mapShaderOption(CallbackInfo callback) {
        if (!GlMapShaderPackRenderer.available()) return;
        addRenderableWidget(Button.builder(earth$shaderLabel(), button -> {
            EarthPreferences.SHADER_PACK.set(!EarthPreferences.SHADER_PACK.get());
            EarthPreferences.save();
            button.setMessage(earth$shaderLabel());
        }).bounds(6, 6, Math.min(150, Math.max(80, width / 3)), 20).build());
    }

    @Unique
    private static Component earth$shaderLabel() {
        return Component.translatable(EarthPreferences.SHADER_PACK.get()
                ? "xaeroearth.shader_pack.enabled" : "xaeroearth.shader_pack.disabled");
    }
}
