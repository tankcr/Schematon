package tankcmod.schematon.world;

import net.minecraft.core.BlockPos;

public class SchematicPlacement {
    private static VirtualSchematicLevel activeSchematic = null;
    private static BlockPos origin = BlockPos.ZERO;

    public static void setActiveSchematic(VirtualSchematicLevel level) {
        activeSchematic = level;
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
}
