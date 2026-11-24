package tankcmod.schematon.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.fml.ModList;

public class AgriCraftCompat implements ISchematicCompat {
    public static final String MODID = "agricraft";
    private final boolean modLoaded;

    public AgriCraftCompat() {
        this.modLoaded = ModList.get().isLoaded(MODID);
    }

    @Override
    public boolean matches(String namespace) {
        return this.modLoaded && MODID.equals(namespace);
    }

    @Override
    public CompoundTag prepareSaveTag(BlockEntity be, CompoundTag rawTag, BlockPos worldPos, Level level) {
        // Preserve AgriCraft crop stick / plant / stat NBT cleanly with normalized coordinates
        CompoundTag copy = rawTag.copy();
        copy.putInt("x", worldPos.getX());
        copy.putInt("y", worldPos.getY());
        copy.putInt("z", worldPos.getZ());
        return copy;
    }

    @Override
    public CompoundTag prepareTag(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel) {
        return tag;
    }

    @Override
    public void onBlockEntityLoad(BlockEntity be, CompoundTag tag, BlockPos localPos, BlockAndTintGetter schematicLevel) {
        // Hook reserved for virtual schematic crop/model-data initialization once verified in-game
    }

    @Override
    public ModelData getModelData(BlockEntity be) {
        return be.getModelData();
    }

    @Override
    public void onBlockEntityPasted(BlockEntity targetBe, CompoundTag appliedTag, BlockPos worldPos, ServerLevel level) {
        targetBe.setChanged();
        BlockState state = targetBe.getBlockState();
        level.sendBlockUpdated(worldPos, state, state, Block.UPDATE_ALL);
    }
}