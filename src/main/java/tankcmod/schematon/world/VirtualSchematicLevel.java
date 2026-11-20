package tankcmod.schematon.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;
import tankcmod.schematon.format.SchematicData;

/**
 * Lightweight read-only virtual world wrapper around {@link SchematicData}.
 * Translates rotated local coordinates back to unrotated schematic indices so
 * BlockRenderDispatcher neighbor face culling and connected models match the active rotation.
 */
public class VirtualSchematicLevel implements BlockAndTintGetter {
    private final SchematicData schematic;
    private final Level realClientLevel;

    public VirtualSchematicLevel(SchematicData schematic, Level realClientLevel) {
        this.schematic = schematic;
        this.realClientLevel = realClientLevel;
    }

    public Vec3i getRotatedSize() {
        return SchematicPlacement.getRotatedSize(schematic.getSize(), SchematicPlacement.getRotation());
    }

    private boolean isInsideRotated(BlockPos pos) {
        Vec3i rotSize = getRotatedSize();
        return pos.getX() >= 0 && pos.getX() < rotSize.getX()
                && pos.getY() >= 0 && pos.getY() < rotSize.getY()
                && pos.getZ() >= 0 && pos.getZ() < rotSize.getZ();
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (!isInsideRotated(pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        Rotation rot = SchematicPlacement.getRotation();
        BlockPos sourcePos = SchematicPlacement.untransformToSourcePos(
                pos.getX(), pos.getY(), pos.getZ(), schematic.getSize(), rot
        );
        return SchematicPlacement.applyRotation(schematic.getBlockState(sourcePos), rot);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        if (!isInsideRotated(pos)) {
            return null;
        }
        Rotation rot = SchematicPlacement.getRotation();
        BlockPos sourcePos = SchematicPlacement.untransformToSourcePos(
                pos.getX(), pos.getY(), pos.getZ(), schematic.getSize(), rot
        );
        return schematic.getOrCreateBlockEntity(sourcePos, realClientLevel, this);
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