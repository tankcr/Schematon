package tankcmod.schematon.world;

import net.minecraft.core.BlockPos;
import tankcmod.schematon.client.render.SchematicRenderer;

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
}