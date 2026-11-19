package io.github.kltyton.xaeroearth.client.mixin.terrain;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.kltyton.kltytonui.chunkmap.KuiChunkMeshBaker;
import io.github.kltyton.xaeroearth.client.integration.create.ContraptionModelScope;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Supplies the real detached ModelData to KUI's layer selection and model bake. */
@Mixin(value = KuiChunkMeshBaker.class, remap = false)
abstract class ContraptionModelDataMixin {
    @ModifyExpressionValue(method = "bake", at = @At(value = "FIELD",
            target = "Lnet/neoforged/neoforge/client/model/data/ModelData;EMPTY:Lnet/neoforged/neoforge/client/model/data/ModelData;"))
    private ModelData earth$modelData(ModelData original, @Local BlockPos.MutableBlockPos position) {
        var block = ContraptionModelScope.entityAt(position);
        return block == null ? original : block.getModelData();
    }
}
