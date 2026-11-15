package io.github.kltyton.xaeroearth.client.render.navigation;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import java.nio.ByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicUniformStorage;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** Samples the scene into a separate GPU color target and preserves its depth attachment. */
final class TerrainScanRenderer {
    private static TextureTarget output;
    private static DynamicUniformStorage<Parameters> parameters;

    static void draw(Minecraft minecraft, Matrix4fc view, Matrix4fc projection, Vec3 camera) {
        TerrainScan.Frame frame = TerrainScan.frame(minecraft.level, camera);
        if (frame == null || frame.intensity() <= 0) return;
        RenderTarget scene = NavigationRenderPlatform.mainTarget(minecraft);
        if (output == null || output.width != scene.width || output.height != scene.height) {
            if (output != null) output.destroyBuffers();
            output = NavigationRenderPlatform.scanTarget(scene);
        }
        if (parameters == null) parameters = new DynamicUniformStorage<>("Earth terrain scan uniforms", 112, 2);
        parameters.endFrame();
        var uniforms = parameters.writeUniform(new Parameters(new Matrix4f(projection).mul(view).invert(), frame));
        var encoder = RenderSystem.getDevice().createCommandEncoder();
        var nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        try (var pass = NavigationRenderPlatform.scanPass(output, encoder)) {
            pass.setPipeline(NavigationRenderPlatform.SCAN_PIPELINE);
            pass.setUniform("TerrainScanParameters", uniforms);
            pass.bindTexture("SceneColor", scene.getColorTextureView(), nearest);
            pass.bindTexture("SceneDepth", scene.getDepthTextureView(), nearest);
            NavigationRenderPlatform.screenTriangle(pass);
        }
        encoder.copyTextureToTexture(output.getColorTexture(), scene.getColorTexture(), 0, 0, 0, 0, 0,
                scene.width, scene.height);
    }

    static void close() {
        if (output != null) { output.destroyBuffers(); output = null; }
        if (parameters != null) { parameters.close(); parameters = null; }
    }

    private record Parameters(Matrix4f worldFromClip, TerrainScan.Frame frame)
            implements DynamicUniformStorage.DynamicUniform {
        @Override public void write(ByteBuffer data) {
            worldFromClip.get(data.position(), data);
            data.position(data.position() + 64);
            Vec3 origin = frame.origin();
            data.putFloat((float) origin.x).putFloat((float) origin.y).putFloat((float) origin.z).putFloat(0);
            data.putFloat(frame.forwardX()).putFloat(frame.forwardZ()).putFloat(frame.sectorCos())
                    .putFloat(NavigationRenderPlatform.zeroToOne() ? 1 : 0);
            data.putFloat(frame.radius()).putFloat(frame.intensity()).putFloat(frame.spacing())
                    .putFloat(NavigationRenderPlatform.REVERSE_DEPTH ? 1 : 0);
        }
    }

    private TerrainScanRenderer() {}
}
