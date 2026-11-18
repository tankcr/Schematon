package tankcmod.schematon.client.printer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import tankcmod.schematon.compat.CompatManager;
import tankcmod.schematon.format.SchematicData;
import tankcmod.schematon.world.SchematicPlacement;
import tankcmod.schematon.world.VirtualSchematicLevel;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

public class SchematicPrinter {
    private static final int TICKS_PER_PLACEMENT = 2; // 10 blocks/sec max to avoid packet spam
    private static int tickCooldown = 0;

    // Placeholder for future ForgeConfigSpec option
    public static boolean requirePrinterMachine = false;

    /**
     * Call this from ClientTickEvent (Phase.END) when Printer is enabled.
     */
    public static void onClientTick(boolean printerEnabled) {
        if (!printerEnabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.screen != null) {
            return;
        }

        VirtualSchematicLevel virtualLevel = SchematicPlacement.getActiveSchematic();
        if (virtualLevel == null || SchematicPlacement.isHidden()) {
            return;
        }

        if (!canUsePrinter(mc.player)) {
            return;
        }

        if (tickCooldown > 0) {
            tickCooldown--;
            return;
        }

        if (tryPlaceNextBlock(mc, mc.player, virtualLevel)) {
            tickCooldown = TICKS_PER_PLACEMENT;
        }
    }

    /**
     * Future hook for the Printer Machine + Config requirement.
     */
    private static boolean canUsePrinter(LocalPlayer player) {
        if (player.isCreative()) {
            return true;
        }
        if (!requirePrinterMachine) {
            return true;
        }
        // TODO: Check for nearby active Printer Machine BlockEntity or handheld Printer item
        return false;
    }

    private static boolean tryPlaceNextBlock(Minecraft mc, LocalPlayer player, VirtualSchematicLevel virtualLevel) {
        SchematicData data = virtualLevel.getSchematic();
        Vec3i size = data.getSize();
        BlockPos origin = SchematicPlacement.getOrigin();

        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        int activeLayerY = SchematicPlacement.getLayerY();

        double reach = player.getBlockReach();
        double reachSq = reach * reach;
        Vec3 eyePos = player.getEyePosition();

        int minX = Math.max(0, (int) Math.floor(eyePos.x - reach) - origin.getX());
        int maxX = Math.min(size.getX() - 1, (int) Math.ceil(eyePos.x + reach) - origin.getX());
        int minY = Math.max(0, (int) Math.floor(eyePos.y - reach) - origin.getY());
        int maxY = Math.min(size.getY() - 1, (int) Math.ceil(eyePos.y + reach) - origin.getY());
        int minZ = Math.max(0, (int) Math.floor(eyePos.z - reach) - origin.getZ());
        int maxZ = Math.min(size.getZ() - 1, (int) Math.ceil(eyePos.z + reach) - origin.getZ());

        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();

        for (int y = minY; y <= maxY; y++) {
            if (mode == SchematicPlacement.LayerMode.SINGLE_LAYER && y != activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_BELOW && y > activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_ABOVE && y < activeLayerY) continue;

            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    BlockState schState = data.getBlockState(x, y, z);
                    if (schState.isAir()) continue;

                    // Resolve item through CompatManager so modded NBT blocks (e.g. AE2 cables) work
                    localPos.set(x, y, z);
                    BlockEntity virtualBe = virtualLevel.getBlockEntity(localPos);
                    ItemStack requiredStack = CompatManager.getRequiredItem(schState, virtualBe, null);
                    if (requiredStack.isEmpty()) continue;

                    BlockPos worldPos = origin.offset(x, y, z);

                    // Exact spherical reach check from player's eyes to block center
                    Vec3 blockCenter = Vec3.atCenterOf(worldPos);
                    if (eyePos.distanceToSqr(blockCenter) > reachSq) {
                        continue;
                    }

                    // Only place into replaceable blocks (air, tall grass, water, etc.)
                    BlockState worldState = mc.level.getBlockState(worldPos);
                    if (!worldState.canBeReplaced()) {
                        continue;
                    }

                    // Don't place a solid block inside the player's own hitbox
                    if (!schState.getCollisionShape(mc.level, worldPos).isEmpty()
                            && player.getBoundingBox().intersects(
                            worldPos.getX(), worldPos.getY(), worldPos.getZ(),
                            worldPos.getX() + 1, worldPos.getY() + 1, worldPos.getZ() + 1)) {
                        continue;
                    }

                    // Find a solid neighboring block to click against
                    Direction placeFace = findSupportFace(mc, worldPos);
                    if (placeFace == null) {
                        continue;
                    }

                    // Pull item into Main Hand (Survival inventory or Creative)
                    if (!prepareItemInMainHand(mc, player, requiredStack)) {
                        continue;
                    }

                    // Standard vanilla block placement, wrapped in a spoofed sneak
                    // so clicking chests, machines, or doors places the block instead of opening a GUI.
                    BlockPos supportPos = worldPos.relative(placeFace);
                    Direction hitSide = placeFace.getOpposite();
                    Vec3 hitVec = Vec3.atCenterOf(supportPos).add(
                            hitSide.getStepX() * 0.5,
                            hitSide.getStepY() * 0.5,
                            hitSide.getStepZ() * 0.5
                    );

                    BlockHitResult hitResult = new BlockHitResult(hitVec, hitSide, supportPos, false);

                    boolean wasSneaking = player.isShiftKeyDown();
                    if (!wasSneaking) {
                        player.setShiftKeyDown(true);
                        player.connection.send(new ServerboundPlayerCommandPacket(
                                player, ServerboundPlayerCommandPacket.Action.PRESS_SHIFT_KEY
                        ));
                    }

                    InteractionResult result;
                    try {
                        result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
                    } finally {
                        if (!wasSneaking) {
                            player.setShiftKeyDown(false);
                            player.connection.send(new ServerboundPlayerCommandPacket(
                                    player, ServerboundPlayerCommandPacket.Action.RELEASE_SHIFT_KEY
                            ));
                        }
                    }

                    if (result.consumesAction()) {
                        player.swing(InteractionHand.MAIN_HAND);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static Direction findSupportFace(Minecraft mc, BlockPos targetPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = targetPos.relative(dir);
            BlockState neighborState = mc.level.getBlockState(neighborPos);
            if (!neighborState.isAir() && !neighborState.canBeReplaced()) {
                return dir;
            }
        }
        return null;
    }

    private static boolean prepareItemInMainHand(Minecraft mc, LocalPlayer player, ItemStack requiredStack) {
        if (ItemStack.isSameItemSameTags(player.getMainHandItem(), requiredStack)) {
            return true;
        }

        Inventory inv = player.getInventory();
        int slot = inv.findSlotMatchingItem(requiredStack);

        if (slot != -1) {
            if (slot < 9) {
                inv.selected = slot;
            } else {
                mc.gameMode.handlePickItem(slot);
            }
            return true;
        }

        if (player.isCreative()) {
            ItemStack creativeStack = requiredStack.copy();
            creativeStack.setCount(1);
            inv.setItem(inv.selected, creativeStack);
            mc.gameMode.handleCreativeModeItemAdd(creativeStack, 36 + inv.selected);
            return true;
        }

        return false;
    }
}