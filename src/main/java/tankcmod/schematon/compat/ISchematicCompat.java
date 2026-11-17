package tankcmod.schematon.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.client.model.data.ModelData;

public interface ISchematicCompat {
    boolean matches(String namespace);

    /** Called BEFORE the BlockEntity loads. Allows modifying raw NBT safely. */
    CompoundTag prepareTag(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel);

    /** Called AFTER the BlockEntity is loaded and the client Level is attached. */
    void onBlockEntityLoad(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel);

    ModelData getModelData(BlockEntity be);
}