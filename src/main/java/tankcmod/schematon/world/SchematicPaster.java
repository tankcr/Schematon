package tankcmod.schematon.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import tankcmod.schematon.compat.CompatManager;
import tankcmod.schematon.format.SchematicData;

import javax.annotation.Nullable;
import java.util.Map;

public class SchematicPaster {

    public record PasteResult(boolean success, int blocksPlaced, int blocksExcavated, int blockEntitiesRestored, String message) {}

    /**
     * Pastes the visible layer(s) of a schematic directly into the ServerLevel,
     * restoring modded BlockEntity NBT via CompatManager.
     *
     * @param blockEntityTags Optional map of local schematic BlockPos -> raw BlockEntity CompoundTag.
     */
    public static PasteResult pasteVisibleLayers(
            ServerLevel level,
            ServerPlayer player,
            SchematicData data,
            @Nullable Map<BlockPos, CompoundTag> blockEntityTags,
            BlockPos origin,
            SchematicPlacement.LayerMode mode,
            int activeLayerY,
            boolean excavateAir
    ) {
        // Strict server-side Creative check
        if (!player.isCreative()) {
            return new PasteResult(false, 0, 0, 0, "Paste is only available in Creative mode.");
        }

        if (data == null) {
            return new PasteResult(false, 0, 0, 0, "No schematic data to paste.");
        }

        Vec3i size = data.getSize();
        int placed = 0;
        int excavated = 0;
        int blockEntitiesRestored = 0;

        // UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE prevents cascading neighbor physics mid-paste
        int setBlockFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();

        for (int y = 0; y < size.getY(); y++) {
            if (mode == SchematicPlacement.LayerMode.SINGLE_LAYER && y != activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_BELOW && y > activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_ABOVE && y < activeLayerY) continue;

            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState schState = data.getBlockState(x, y, z);
                    BlockPos worldPos = origin.offset(x, y, z);

                    // Skip unloaded chunks to avoid stalling the server thread
                    if (!level.isLoaded(worldPos)) {
                        continue;
                    }

                    BlockState currentWorldState = level.getBlockState(worldPos);

                    if (schState.isAir()) {
                        if (excavateAir && !currentWorldState.isAir()) {
                            level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), setBlockFlags);
                            excavated++;
                        }
                        continue;
                    }

                    // Place block if state differs
                    if (!currentWorldState.equals(schState)) {
                        level.setBlock(worldPos, schState, setBlockFlags);
                        placed++;
                    }

                    // Restore BlockEntity NBT through CompatManager if present
                    if (blockEntityTags != null && !blockEntityTags.isEmpty()) {
                        localPos.set(x, y, z);
                        CompoundTag rawTag = blockEntityTags.get(localPos);
                        if (rawTag != null) {
                            BlockEntity targetBe = level.getBlockEntity(worldPos);
                            if (targetBe != null) {
                                CompoundTag preparedTag = CompatManager.preparePasteTag(targetBe, rawTag, worldPos, level);
                                targetBe.load(preparedTag);
                                CompatManager.onBlockEntityPasted(targetBe, preparedTag, worldPos, level);
                                blockEntitiesRestored++;
                            }
                        }
                    }
                }
            }
        }

        String summary = "Pasted " + placed + " blocks"
                + (blockEntitiesRestored > 0 ? ", " + blockEntitiesRestored + " block entities" : "")
                + (excavateAir ? " (excavated " + excavated + ")" : "") + ".";
        return new PasteResult(true, placed, excavated, blockEntitiesRestored, summary);
    }
}