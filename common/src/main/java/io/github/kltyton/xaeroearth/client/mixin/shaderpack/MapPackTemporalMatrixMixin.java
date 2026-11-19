package io.github.kltyton.xaeroearth.client.mixin.shaderpack;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.kltyton.xaeroearth.client.render.shaderpack.MapPackTarget;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Slice;

/** Uses the preceding completed map frame in the temporal uniforms of the map's own pack pipeline. */
@Pseudo
@Mixin(targets = {"net.irisshaders.iris.uniforms.MatrixUniforms", "net.coderbot.iris.uniforms.MatrixUniforms"}, remap = false)
abstract class MapPackTemporalMatrixMixin {
    @ModifyArg(method = "addMatrix", require = 0, index = 2,
            slice = @Slice(from = @At(value = "NEW", target = "Lnet/irisshaders/iris/uniforms/MatrixUniforms$Previous;")),
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/gl/uniform/UniformHolder;uniformMatrix(Lnet/irisshaders/iris/gl/uniform/UniformUpdateFrequency;Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/irisshaders/iris/gl/uniform/UniformHolder;"))
    private static Supplier<?> earth$irisPrevious(Supplier<?> supplier, @Local(argsOnly = true) String matrix) {
        return earth$bindPrevious(matrix, supplier);
    }

    @ModifyArg(method = "addMatrix", require = 0, index = 2,
            slice = @Slice(from = @At(value = "NEW", target = "Lnet/coderbot/iris/uniforms/MatrixUniforms$Previous;")),
            at = @At(value = "INVOKE", target = "Lnet/coderbot/iris/gl/uniform/UniformHolder;uniformMatrix(Lnet/coderbot/iris/gl/uniform/UniformUpdateFrequency;Ljava/lang/String;Ljava/util/function/Supplier;)Lnet/coderbot/iris/gl/uniform/UniformHolder;"))
    private static Supplier<?> earth$oculusPrevious(Supplier<?> supplier, @Local(argsOnly = true) String matrix) {
        return earth$bindPrevious(matrix, supplier);
    }

    @Unique private static Supplier<?> earth$bindPrevious(String matrix, Supplier<?> nativeHistory) {
        if (MapPackTarget.current() == null || !("ModelView".equals(matrix) || "Projection".equals(matrix)))
            return nativeHistory;
        boolean projection = "Projection".equals(matrix);
        return () -> {
            Object mapHistory = MapPackTarget.previousMatrix(projection);
            return mapHistory == null ? nativeHistory.get() : mapHistory;
        };
    }
}
