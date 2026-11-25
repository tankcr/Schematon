package tankcmod.schematon.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import tankcmod.schematon.compat.CompatManager;
import tankcmod.schematon.format.SchematicData;
import tankcmod.schematon.config.SchematonConfig;
import tankcmod.schematon.world.SchematicChunkLoader;
import java.util.ArrayList;

import javax.annotation.Nullable;
import java.util.Map;

public class SchematicPaster {
    private static SchematonConfig.PasteMode getEffectivePasteMode() {
        try {
            if (SchematonConfig.SPEC.isLoaded()) {
                return SchematonConfig.ALLOW_PASTE.get();
            }
        } catch (Exception ignored) {
            // Default to OpOnly if config isn't loaded
        }
        return SchematonConfig.PasteMode.OpOnly;
    }
    public record PasteResult(boolean success, int blocksPlaced, int blocksExcavated, int blockEntitiesRestored, String message) {}

    public static PasteResult pasteVisibleLayers(
            ServerLevel level,
            ServerPlayer player,
            SchematicData data,
            @Nullable Map<BlockPos, CompoundTag> blockEntityTags,
            BlockPos origin,
            Rotation rotation,
            SchematicPlacement.LayerMode mode,
            int activeLayerY,
            boolean excavateAir
    ) {
        SchematonConfig.PasteMode pasteMode = getEffectivePasteMode();
        if (pasteMode == SchematonConfig.PasteMode.Disabled) {
            return new PasteResult(false, 0, 0, 0, "Paste Mode is disabled by server configuration.");
        }

        boolean allowedByMode = switch (pasteMode) {
            case All -> true;
            case OpOnly -> player.isCreative() || player.hasPermissions(2);
            case Disabled -> false;
        };

        if (!allowedByMode) {
            return new PasteResult(false, 0, 0, 0, "Paste requires operator permissions or Creative mode.");
        }

        if (data == null) {
            return new PasteResult(false, 0, 0, 0, "No schematic data to paste.");
        }

        Rotation effectiveRotation = (rotation != null) ? rotation : Rotation.NONE;
        Vec3i size = data.getSize();
        Vec3i rotatedSize = SchematicPlacement.getRotatedSize(size, effectiveRotation);
        int placed = 0;
        int excavated = 0;
        int blockEntitiesRestored = 0;

        int setBlockFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

        for (int y = 0; y < rotatedSize.getY(); y++) {
            if (mode == SchematicPlacement.LayerMode.SINGLE_LAYER && y != activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_BELOW && y > activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_ABOVE && y < activeLayerY) continue;

            for (int rz = 0; rz < rotatedSize.getZ(); rz++) {
                for (int rx = 0; rx < rotatedSize.getX(); rx++) {
                    BlockPos sourcePos = SchematicPlacement.untransformToSourcePos(rx, y, rz, size, effectiveRotation);
                    BlockState rawState = data.getBlockState(sourcePos.getX(), sourcePos.getY(), sourcePos.getZ());
                    BlockState schState = SchematicPlacement.applyRotation(rawState, effectiveRotation);
                    BlockPos worldPos = origin.offset(rx, y, rz);

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

                    if (!currentWorldState.equals(schState)) {
                        level.setBlock(worldPos, schState, setBlockFlags);
                        placed++;
                    }

                    if (blockEntityTags != null && !blockEntityTags.isEmpty()) {
                        CompoundTag rawTag = blockEntityTags.get(sourcePos);
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