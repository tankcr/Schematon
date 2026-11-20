package tankcmod.schematon.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import tankcmod.schematon.client.render.SchematicRenderer;

public class SchematicPlacement {
    public enum LayerMode {
        ALL, ALL_BELOW, ALL_ABOVE, SINGLE_LAYER
    }

    private static VirtualSchematicLevel activeSchematic = null;
    private static BlockPos origin = BlockPos.ZERO;

    private static LayerMode layerMode = LayerMode.ALL;
    private static int layerY = 0;
    private static boolean hidden = false;
    private static boolean excavateAir = true;
    private static boolean showMaterialsHud = false;
    private static boolean printerEnabled = false;
    private static Rotation rotation = Rotation.NONE;

    public static Rotation getRotation() {
        return rotation;
    }

    public static void setRotation(Rotation newRotation) {
        Rotation target = (newRotation != null) ? newRotation : Rotation.NONE;
        if (rotation != target) {
            rotation = target;
            SchematicRenderer.markDirty();
        }
    }

    /** Cycles: 0° -> 90° -> 180° -> -90° -> 0° */
    public static void rotateClockwise() {
        rotation = rotation.getRotated(Rotation.CLOCKWISE_90);
        SchematicRenderer.markDirty();
    }

    /** Cycles: 0° -> -90° -> 180° -> 90° -> 0° */
    public static void rotateCounterClockwise() {
        rotation = rotation.getRotated(Rotation.COUNTERCLOCKWISE_90);
        SchematicRenderer.markDirty();
    }

    public static String getRotationLabel(Rotation rot) {
        return switch (rot) {
            case NONE -> "0°";
            case CLOCKWISE_90 -> "90°";
            case CLOCKWISE_180 -> "180°";
            case COUNTERCLOCKWISE_90 -> "-90°";
        };
    }

    /** Swaps X and Z dimensions when rotated 90° or -90°. */
    public static Vec3i getRotatedSize(Vec3i originalSize, Rotation rot) {
        if (rot == Rotation.CLOCKWISE_90 || rot == Rotation.COUNTERCLOCKWISE_90) {
            return new Vec3i(originalSize.getZ(), originalSize.getY(), originalSize.getX());
        }
        return originalSize;
    }

    /**
     * Maps a rotated local coordinate (0..rotatedSize-1) back to the original
     * unrotated schematic index (0..originalSize-1) for block & BlockEntity lookup.
     */
    public static BlockPos untransformToSourcePos(int rx, int y, int rz, Vec3i originalSize, Rotation rot) {
        return switch (rot) {
            case NONE -> new BlockPos(rx, y, rz);
            case CLOCKWISE_90 -> new BlockPos(rz, y, originalSize.getZ() - 1 - rx);
            case CLOCKWISE_180 -> new BlockPos(originalSize.getX() - 1 - rx, y, originalSize.getZ() - 1 - rz);
            case COUNTERCLOCKWISE_90 -> new BlockPos(originalSize.getX() - 1 - rz, y, rx);
        };
    }

    /** Rotates directional BlockState properties (FACING, AXIS, stairs, rails, etc.). */
    public static BlockState applyRotation(BlockState state, Rotation rot) {
        return (state == null || state.isAir() || rot == Rotation.NONE) ? state : state.rotate(rot);
    }

    /** Centers the ROTATED bounding box in front of the player. */
    public static BlockPos calculateFrontOrigin(Player player, Vec3i size, int gapBlocks) {
        Vec3i rotatedSize = getRotatedSize(size, rotation);
        BlockPos playerPos = player.blockPosition();
        Direction facing = player.getDirection();

        int x = playerPos.getX();
        int y = playerPos.getY();
        int z = playerPos.getZ();

        int halfX = rotatedSize.getX() / 2;
        int halfZ = rotatedSize.getZ() / 2;

        return switch (facing) {
            case SOUTH -> new BlockPos(x - halfX, y, z + gapBlocks);
            case NORTH -> new BlockPos(x - halfX, y, z - gapBlocks - rotatedSize.getZ() + 1);
            case EAST  -> new BlockPos(x + gapBlocks, y, z - halfZ);
            case WEST  -> new BlockPos(x - gapBlocks - rotatedSize.getX() + 1, y, z - halfZ);
            default    -> playerPos;
        };
    }

    public static boolean isShowMaterialsHud() {
        return showMaterialsHud;
    }

    public static void setShowMaterialsHud(boolean show) {
        showMaterialsHud = show;
    }

    public static void toggleMaterialsHud() {
        showMaterialsHud = !showMaterialsHud;
    }

    public static void setActiveSchematic(VirtualSchematicLevel level) {
        activeSchematic = level;
        rotation = Rotation.NONE;
        SchematicRenderer.markDirty();
    }

    public static VirtualSchematicLevel getActiveSchematic() {
        return activeSchematic;
    }

    public static BlockPos getOrigin() {
        return origin;
    }

    public static void setOrigin(BlockPos pos) {
        origin = pos;
    }

    public static void move(int x, int y, int z) {
        origin = origin.offset(x, y, z);
    }

    public static LayerMode getLayerMode() {
        return layerMode;
    }

    public static void setLayerMode(LayerMode mode) {
        if (layerMode != mode) {
            layerMode = mode;
            SchematicRenderer.markDirty();
        }
    }

    public static int getLayerY() {
        return layerY;
    }

    public static void setLayerY(int y) {
        if (layerY != y) {
            layerY = y;
            SchematicRenderer.markDirty();
        }
    }

    public static boolean isHidden() {
        return hidden;
    }

    public static void setHidden(boolean state) {
        hidden = state;
    }

    public static boolean isExcavateAir() {
        return excavateAir;
    }

    public static void setExcavateAir(boolean excavate) {
        excavateAir = excavate;
    }

    public static boolean isPrinterEnabled() {
        return printerEnabled;
    }

    public static void setPrinterEnabled(boolean enabled) {
        printerEnabled = enabled;
    }

    public static void togglePrinter() {
        printerEnabled = !printerEnabled;
    }
}