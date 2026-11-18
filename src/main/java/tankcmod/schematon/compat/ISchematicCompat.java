package tankcmod.schematon.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;

import javax.annotation.Nullable;

public interface ISchematicCompat {
    boolean matches(String namespace);

    /** Called BEFORE the virtual BlockEntity loads on the client. Allows modifying raw NBT safely. */
    CompoundTag prepareTag(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel);

    /** Called AFTER the virtual BlockEntity is loaded and the client Level is attached. */
    void onBlockEntityLoad(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel);

    ModelData getModelData(BlockEntity be);

    /**
     * Resolves the ItemStack required by the Printer (or Materials list) to place this block.
     * Override for multipart/NBT-backed blocks like AE2 cables or FramedBlocks.
     */
    default ItemStack getRequiredItem(BlockState state, @Nullable BlockEntity virtualBe, @Nullable CompoundTag beTag) {
        return new ItemStack(state.getBlock().asItem());
    }

    /**
     * Sanitizes raw schematic NBT before loading it into a real server-side BlockEntity during Creative Paste.
     * Always stamps the real world coordinates so the BlockEntity doesn't bind to stale local coordinates.
     */
    default CompoundTag preparePasteTag(BlockEntity targetBe, CompoundTag rawTag, BlockPos worldPos, ServerLevel level) {
        CompoundTag copy = rawTag.copy();
        copy.putInt("x", worldPos.getX());
        copy.putInt("y", worldPos.getY());
        copy.putInt("z", worldPos.getZ());
        return copy;
    }

    /**
     * Called on the server AFTER the pasted BlockEntity has loaded its NBT.
     */
    default void onBlockEntityPasted(BlockEntity targetBe, CompoundTag appliedTag, BlockPos worldPos, ServerLevel level) {
        targetBe.setChanged();
        BlockState state = targetBe.getBlockState();
        level.sendBlockUpdated(worldPos, state, state, Block.UPDATE_ALL);
    }
}