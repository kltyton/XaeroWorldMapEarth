package io.github.kltyton.xaeroearth.client.render.entity;

import io.github.kltyton.kltytonui.neoforge.RenderService;
import net.minecraft.client.renderer.SubmitNodeStorage;

final class NativeEntityFeatures {
    static void dispatch(SubmitNodeStorage nodes) {
        RenderService.INSTANCE.dispatchNativeFeatures(nodes);
    }

    private NativeEntityFeatures() { }
}
