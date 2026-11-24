package tankcmod.schematon.client.create;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import tankcmod.schematon.client.render.SelectionRenderer;

import javax.annotation.Nullable;

public class SchematicSelection {
    @Nullable
    private static BlockPos pos1 = null;

    @Nullable
    private static BlockPos pos2 = null;

    private static boolean selectionActive = false;
    private static boolean clickToSelectEnabled = true;
    private static boolean nextClickSetsPos1 = true;

    public static boolean isSelectionActive() {
        return selectionActive;
    }

    public static void setSelectionActive(boolean active) {
        if (selectionActive != active) {
            selectionActive = active;
            SelectionRenderer.markDirty();
        }
    }

    public static boolean isClickToSelectEnabled() {
        return clickToSelectEnabled;
    }

    public static void setClickToSelectEnabled(boolean enabled) {
        clickToSelectEnabled = enabled;
    }

    public static void toggleClickToSelect() {
        clickToSelectEnabled = !clickToSelectEnabled;
    }

    /**
     * Ensures Pos 1 and Pos 2 have sensible defaults around anchorPos if unset.
     */
    public static void ensureInitialized(BlockPos anchorPos) {
        if (pos1 == null && pos2 == null) {
            pos1 = anchorPos;
            pos2 = anchorPos.offset(3, 3, 3);
        } else if (pos1 == null) {
            pos1 = pos2;
        } else if (pos2 == null) {
            pos2 = pos1;
        }
        selectionActive = true;
        SelectionRenderer.markDirty();
    }

    @Nullable
    public static BlockPos getPos1() {
        return pos1;
    }

    @Nullable
    public static BlockPos getPos2() {
        return pos2;
    }

    public static void setPos1(@Nullable BlockPos newPos1) {
        pos1 = (newPos1 != null) ? newPos1.immutable() : null;
        if (pos1 != null) {
            selectionActive = true;
        }
        SelectionRenderer.markDirty();
    }

    public static void setPos2(@Nullable BlockPos newPos2) {
        pos2 = (newPos2 != null) ? newPos2.immutable() : null;
        if (pos2 != null) {
            selectionActive = true;
        }
        SelectionRenderer.markDirty();
    }

    public static void movePos1(int dx, int dy, int dz) {
        if (pos1 == null) return;
        pos1 = pos1.offset(dx, dy, dz);
        selectionActive = true;
        SelectionRenderer.markDirty();
    }

    public static void movePos2(int dx, int dy, int dz) {
        if (pos2 == null) return;
        pos2 = pos2.offset(dx, dy, dz);
        selectionActive = true;
        SelectionRenderer.markDirty();
    }

    /**
     * Records a world right-click position: alternates between Pos 1 (Blue) and Pos 2 (Red),
     * or initializes both on the first click if neither corner was placed yet.
     */
    public static void recordWorldClick(BlockPos clickedPos) {
        BlockPos immutable = clickedPos.immutable();
        if (pos1 == null && pos2 == null) {
            pos1 = immutable;
            pos2 = immutable;
            nextClickSetsPos1 = false;
        } else if (nextClickSetsPos1) {
            pos1 = immutable;
            nextClickSetsPos1 = false;
        } else {
            pos2 = immutable;
            nextClickSetsPos1 = true;
        }
        selectionActive = true;
        SelectionRenderer.markDirty();
    }

    public static void setNextClickSetsPos1(boolean setPos1Next) {
        nextClickSetsPos1 = setPos1Next;
    }

    public static boolean isNextClickSetsPos1() {
        return nextClickSetsPos1;
    }

    public static boolean hasCompleteRegion() {
        return pos1 != null && pos2 != null;
    }

    @Nullable
    public static BlockPos getMinPos() {
        if (pos1 == null || pos2 == null) return null;
        return new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
        );
    }

    @Nullable
    public static BlockPos getMaxPos() {
        if (pos1 == null || pos2 == null) return null;
        return new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        );
    }

    @Nullable
    public static Vec3i getSize() {
        BlockPos min = getMinPos();
        BlockPos max = getMaxPos();
        if (min == null || max == null) return null;
        return new Vec3i(
                (max.getX() - min.getX()) + 1,
                (max.getY() - min.getY()) + 1,
                (max.getZ() - min.getZ()) + 1
        );
    }

    public static void clear() {
        pos1 = null;
        pos2 = null;
        selectionActive = false;
        nextClickSetsPos1 = true;
        SelectionRenderer.markDirty();
    }
}