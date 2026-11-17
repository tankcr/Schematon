package tankcmod.schematon.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.registries.ForgeRegistries;

public class AE2Compat implements ISchematicCompat {
    @Override
    public boolean matches(String namespace) {
        return "ae2".equals(namespace);
    }

    @Override
    public CompoundTag prepareTag(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel) {
        CompoundTag workingTag = tag.copy();
        // Auto-detect adjacent cables/machines if connections are missing from the converter
        ensureCableConnections(workingTag, localPos, schematicLevel);
        return workingTag;
    }

    @Override
    public void onBlockEntityLoad(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel) {
        // Because the NBT was loaded while level was null, parts are now properly initialized!
        // We just trigger AE2's internal model state rebuild hooks safely.
        be.onLoad();
        be.requestModelDataUpdate();
    }

    @Override
    public ModelData getModelData(BlockEntity be) {
        return be.getModelData();
    }

    private void ensureCableConnections(CompoundTag tag, BlockPos localPos, BlockAndTintGetter level) {
        if (level == null || !tag.contains("cable", Tag.TAG_COMPOUND)) {
            return;
        }

        CompoundTag cableTag = tag.getCompound("cable");
        CompoundTag visualTag = cableTag.contains("visual", Tag.TAG_COMPOUND)
                ? cableTag.getCompound("visual")
                : new CompoundTag();

        ListTag existingConnections = visualTag.getList("connections", Tag.TAG_STRING);
        if (!existingConnections.isEmpty()) {
            return;
        }

        ListTag inferredConnections = new ListTag();
        for (Direction dir : Direction.values()) {
            if (tag.contains(dir.getName(), Tag.TAG_COMPOUND) || isConnectableAE2Neighbor(level.getBlockState(localPos.relative(dir)))) {
                inferredConnections.add(StringTag.valueOf(dir.getName()));
            }
        }

        visualTag.put("connections", inferredConnections);
        cableTag.put("visual", visualTag);
        tag.put("cable", cableTag);
    }

    private boolean isConnectableAE2Neighbor(BlockState neighborState) {
        if (neighborState.isAir()) return false;
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(neighborState.getBlock());
        if (blockId == null || !"ae2".equals(blockId.getNamespace())) return false;

        String path = blockId.getPath();
        return path.equals("cable_bus") || path.equals("controller") || path.equals("interface")
                || path.equals("drive") || path.equals("molecular_assembler") || path.equals("crafting_accelerator")
                || path.endsWith("crafting_storage") || path.equals("crafting_monitor") || path.equals("wireless_access_point")
                || path.equals("inscriber") || path.equals("charger") || path.equals("dense_energy_cell") || path.equals("energy_cell");
    }
}