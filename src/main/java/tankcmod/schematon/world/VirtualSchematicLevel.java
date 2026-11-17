package tankcmod.schematon.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import tankcmod.schematon.format.SchematicData;

/**
 * Lightweight read-only virtual world wrapper around {@link SchematicData}.
 * Allows Minecraft's native BlockRenderDispatcher (and modded baked models like Create)
 * to calculate neighbor face culling, fluid states, and tints safely on the client.
 */
public class VirtualSchematicLevel implements BlockAndTintGetter {
    private final SchematicData schematic;
    private final Level realClientLevel;

    public VirtualSchematicLevel(SchematicData schematic, Level realClientLevel) {
        this.schematic = schematic;
        this.realClientLevel = realClientLevel;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (!schematic.isInside(pos.getX(), pos.getY(), pos.getZ())) {
            return Blocks.AIR.defaultBlockState();
        }
        return schematic.getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        if (!schematic.isInside(pos.getX(), pos.getY(), pos.getZ())) {
            return null;
        }
        return schematic.getOrCreateBlockEntity(pos, realClientLevel);
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return realClientLevel.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return realClientLevel.getLightEngine();
    }

    @Override
    public int getBrightness(LightLayer lightType, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return realClientLevel.getBlockTint(pos, colorResolver);
    }

    @Override
    public int getHeight() {
        return schematic.getSize().getY();
    }

    @Override
    public int getMinBuildHeight() {
        return 0;
    }

    public SchematicData getSchematic() {
        return schematic;
    }
}
