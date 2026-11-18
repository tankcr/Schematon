package tankcmod.schematon.world;

import net.minecraft.core.BlockPos;
import tankcmod.schematon.client.render.SchematicRenderer;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Player;

public class SchematicPlacement {
    public enum LayerMode {
        ALL, ALL_BELOW, ALL_ABOVE, SINGLE_LAYER // ADDED: ALL_ABOVE
    }

    private static VirtualSchematicLevel activeSchematic = null;
    private static BlockPos origin = BlockPos.ZERO;

    private static LayerMode layerMode = LayerMode.ALL;
    private static int layerY = 0;
    private static boolean hidden = false; // ADDED: hidden state
    private static boolean excavateAir = true;
    private static boolean showMaterialsHud = false;
    private static boolean printerEnabled = false;
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

    /**
     * Calculates an origin that places the schematic cleanly in front of the player,
     * centered on their horizontal view, regardless of which cardinal direction they face.
     */
    public static BlockPos calculateFrontOrigin(Player player, Vec3i size, int gapBlocks) {
        BlockPos playerPos = player.blockPosition();
        Direction facing = player.getDirection(); // Horizontal cardinal direction (N/S/E/W)

        int x = playerPos.getX();
        int y = playerPos.getY();
        int z = playerPos.getZ();

        int halfX = size.getX() / 2;
        int halfZ = size.getZ() / 2;

        return switch (facing) {
            case SOUTH -> new BlockPos(x - halfX, y, z + gapBlocks);
            case NORTH -> new BlockPos(x - halfX, y, z - gapBlocks - size.getZ() + 1);
            case EAST  -> new BlockPos(x + gapBlocks, y, z - halfZ);
            case WEST  -> new BlockPos(x - gapBlocks - size.getX() + 1, y, z - halfZ);
            default    -> playerPos;
        };
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

    // ADDED: Getters and setters for hidden state
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