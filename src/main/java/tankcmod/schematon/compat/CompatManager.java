package tankcmod.schematon.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class CompatManager {
    private static final List<ISchematicCompat> HANDLERS = new ArrayList<>();

    static {
        HANDLERS.add(new AE2Compat());
    }

    public static CompoundTag prepareTag(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel) {
        if (be == null) return tag;
        ISchematicCompat handler = findHandler(be);
        return handler != null ? handler.prepareTag(be, tag, localPos, schematicLevel) : tag;
    }

    public static void onBlockEntityLoad(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel) {
        if (be == null) return;
        ISchematicCompat handler = findHandler(be);
        if (handler != null) {
            handler.onBlockEntityLoad(be, tag, localPos, schematicLevel);
        }
    }

    public static ModelData getModelData(BlockEntity be) {
        if (be == null) return ModelData.EMPTY;
        ISchematicCompat handler = findHandler(be);
        return handler != null ? handler.getModelData(be) : be.getModelData();
    }

    /**
     * Resolves the item needed to place a schematic block (handles modded blocks where Block#asItem is AIR).
     */
    public static ItemStack getRequiredItem(BlockState state, @Nullable BlockEntity virtualBe, @Nullable CompoundTag beTag) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId != null) {
            ISchematicCompat handler = findHandlerByNamespace(blockId.getNamespace());
            if (handler != null) {
                return handler.getRequiredItem(state, virtualBe, beTag);
            }
        }
        return new ItemStack(state.getBlock().asItem());
    }

    /**
     * Prepares BlockEntity NBT for server-side Creative Paste.
     */
    public static CompoundTag preparePasteTag(BlockEntity targetBe, CompoundTag rawTag, BlockPos worldPos, ServerLevel level) {
        ISchematicCompat handler = findHandler(targetBe);
        if (handler != null) {
            return handler.preparePasteTag(targetBe, rawTag, worldPos, level);
        }
        CompoundTag copy = rawTag.copy();
        copy.putInt("x", worldPos.getX());
        copy.putInt("y", worldPos.getY());
        copy.putInt("z", worldPos.getZ());
        return copy;
    }

    /**
     * Finalizes a server-side pasted BlockEntity (dirty flag, client sync, and mod-specific network updates).
     */
    public static void onBlockEntityPasted(BlockEntity targetBe, CompoundTag appliedTag, BlockPos worldPos, ServerLevel level) {
        ISchematicCompat handler = findHandler(targetBe);
        if (handler != null) {
            handler.onBlockEntityPasted(targetBe, appliedTag, worldPos, level);
            return;
        }
        targetBe.setChanged();
        BlockState state = targetBe.getBlockState();
        level.sendBlockUpdated(worldPos, state, state, Block.UPDATE_ALL);
    }

    @Nullable
    private static ISchematicCompat findHandler(@Nullable BlockEntity be) {
        if (be == null) return null;
        ResourceLocation id = ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(be.getType());
        return id != null ? findHandlerByNamespace(id.getNamespace()) : null;
    }

    @Nullable
    private static ISchematicCompat findHandlerByNamespace(String namespace) {
        for (ISchematicCompat handler : HANDLERS) {
            if (handler.matches(namespace)) {
                return handler;
            }
        }
        return null;
    }
}