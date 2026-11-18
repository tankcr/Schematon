package tankcmod.schematon.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import tankcmod.schematon.format.SchematicData;
import tankcmod.schematon.world.SchematicPlacement;
import tankcmod.schematon.world.VirtualSchematicLevel;

import java.util.*;

public class MaterialsHudOverlay {
    private static final int MAX_VISIBLE_ROWS = 10;
    private static final int REFRESH_INTERVAL_TICKS = 10; // Full world scan twice per second

    private static int tickCounter = 0;
    private static Item lastHeldItem = Items.AIR;

    private static int cachedTotal = 0;
    private static int cachedRemaining = 0;
    private static List<HudMaterialRow> cachedRows = Collections.emptyList();

    // priorityTier: 0 = Main Hand, 1 = Hotbar/Offhand, 2 = Main Inventory, 3 = Not Carried
    private record HudMaterialRow(ItemStack stack, String name, int remaining, int total, int priorityTier) {}

    /**
     * Call from ClientTickEvent (Phase.END).
     * Re-scans immediately if the player switches their held item, otherwise every 10 ticks.
     */
    public static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (!SchematicPlacement.isShowMaterialsHud()
                || SchematicPlacement.getActiveSchematic() == null
                || mc.player == null) {
            return;
        }

        Item currentHeld = mc.player.getMainHandItem().getItem();
        boolean heldChanged = (currentHeld != lastHeldItem);
        lastHeldItem = currentHeld;

        tickCounter++;
        if (heldChanged || tickCounter >= REFRESH_INTERVAL_TICKS) {
            tickCounter = 0;
            refreshCache();
        }
    }

    public static void refreshCache() {
        Minecraft mc = Minecraft.getInstance();
        VirtualSchematicLevel virtualLevel = SchematicPlacement.getActiveSchematic();
        if (virtualLevel == null || mc.level == null || mc.player == null) {
            cachedRows = Collections.emptyList();
            cachedTotal = 0;
            cachedRemaining = 0;
            return;
        }

        // 1. Snapshot player inventory sets for O(1) tier lookup
        Item heldItem = mc.player.getMainHandItem().getItem();
        Set<Item> hotbarItems = new HashSet<>();
        Set<Item> mainInvItems = new HashSet<>();

        Item offhandItem = mc.player.getOffhandItem().getItem();
        if (offhandItem != Items.AIR) {
            hotbarItems.add(offhandItem);
        }

        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (stack.isEmpty()) continue;

            if (i < 9) {
                hotbarItems.add(stack.getItem());
            } else {
                mainInvItems.add(stack.getItem());
            }
        }

        // 2. Scan active schematic slice against world blocks
        SchematicData data = virtualLevel.getSchematic();
        Vec3i size = data.getSize();
        BlockPos origin = SchematicPlacement.getOrigin();
        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        int activeLayerY = SchematicPlacement.getLayerY();

        // int[0] = total required, int[1] = remaining to place
        Map<Item, int[]> counts = new HashMap<>();
        int total = 0;
        int remaining = 0;

        for (int y = 0; y < size.getY(); y++) {
            if (mode == SchematicPlacement.LayerMode.SINGLE_LAYER && y != activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_BELOW && y > activeLayerY) continue;
            if (mode == SchematicPlacement.LayerMode.ALL_ABOVE && y < activeLayerY) continue;

            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    BlockState schState = data.getBlockState(x, y, z);
                    if (schState.isAir()) continue;

                    Item item = schState.getBlock().asItem();
                    if (item == Items.AIR) continue;

                    BlockState worldState = mc.level.getBlockState(origin.offset(x, y, z));
                    boolean isPlaced = (schState.getBlock() == worldState.getBlock());

                    int[] entry = counts.computeIfAbsent(item, k -> new int[2]);
                    entry[0]++;
                    total++;

                    if (!isPlaced) {
                        entry[1]++;
                        remaining++;
                    }
                }
            }
        }

        // 3. Build rows & assign priority tiers
        List<HudMaterialRow> rows = new ArrayList<>();
        for (Map.Entry<Item, int[]> e : counts.entrySet()) {
            Item item = e.getKey();
            int itemTotal = e.getValue()[0];
            int itemRemaining = e.getValue()[1];

            // Keep the held item visible even if 0 remaining so the player sees it turn green;
            // hide all other completed items to keep the 10-row HUD focused.
            boolean isHeld = (item == heldItem && heldItem != Items.AIR);
            if (itemRemaining == 0 && !isHeld) {
                continue;
            }

            int tier;
            if (isHeld) {
                tier = 0;
            } else if (hotbarItems.contains(item)) {
                tier = 1;
            } else if (mainInvItems.contains(item)) {
                tier = 2;
            } else {
                tier = 3;
            }

            ItemStack stack = new ItemStack(item);
            rows.add(new HudMaterialRow(stack, stack.getHoverName().getString(), itemRemaining, itemTotal, tier));
        }

        // 4. Sort: Priority Tier (asc) -> Remaining Count (desc) -> Name (asc)
        rows.sort(
                Comparator.comparingInt(HudMaterialRow::priorityTier)
                        .thenComparing(Comparator.comparingInt(HudMaterialRow::remaining).reversed())
                        .thenComparing(HudMaterialRow::name)
        );

        cachedTotal = total;
        cachedRemaining = remaining;
        cachedRows = rows;
    }

    public static final IGuiOverlay HUD_MATERIALS = (gui, graphics, partialTick, screenWidth, screenHeight) -> {
        Minecraft mc = Minecraft.getInstance();
        if (!SchematicPlacement.isShowMaterialsHud()) return;
        if (SchematicPlacement.getActiveSchematic() == null) return;
        if (mc.options.hideGui || mc.screen != null) return;

        Font font = mc.font;
        int margin = 12;
        int rowHeight = 18;
        int visibleCount = Math.min(cachedRows.size(), MAX_VISIBLE_ROWS);
        boolean hasMore = cachedRows.size() > MAX_VISIBLE_ROWS;

        int panelW = 215;
        int headerH = 22;
        int footerH = hasMore ? 14 : 4;
        int panelH = headerH + Math.max(1, visibleCount) * rowHeight + footerH;

        int panelX = margin;
        int panelY = screenHeight - panelH - margin;

        // 70% transparent backdrop (matches GuiSchematicControl & GuiSchematicMaterials)
        graphics.fill(panelX - 2, panelY - 2, panelX + panelW + 2, panelY + panelH + 2, 0x4D000000);

        // Header
        graphics.drawString(font, "Materials", panelX + 6, panelY + 6, 0xFFFFFF);
        String summary = "Left: " + cachedRemaining + "/" + cachedTotal;
        int summaryColor = (cachedRemaining == 0 && cachedTotal > 0) ? 0x55FF55 : 0xCCCCCC;
        graphics.drawString(font, summary, panelX + panelW - font.width(summary) - 6, panelY + 6, summaryColor);

        // Divider
        graphics.fill(panelX + 4, panelY + 18, panelX + panelW - 4, panelY + 19, 0x55FFFFFF);

        // Rows
        int y = panelY + headerH;
        if (cachedRows.isEmpty()) {
            graphics.drawString(font, "All blocks placed!", panelX + 6, y + 4, 0x55FF55);
        } else {
            for (int i = 0; i < visibleCount; i++) {
                HudMaterialRow row = cachedRows.get(i);

                // Subtle cyan highlight strip for the currently held block (Tier 0)
                if (row.priorityTier() == 0) {
                    graphics.fill(panelX + 2, y, panelX + panelW - 2, y + rowHeight - 1, 0x3355FFFF);
                    graphics.fill(panelX + 2, y, panelX + 4, y + rowHeight - 1, 0xFF55FFFF);
                }

                graphics.renderFakeItem(row.stack(), panelX + 6, y);

                String countText = row.remaining() + "/" + row.total();
                int countW = font.width(countText);
                int countColor;
                if (row.remaining() == 0) {
                    countColor = 0x55FF55; // Green: complete
                } else if (row.remaining() < row.total()) {
                    countColor = 0xFFFF55; // Yellow: in progress
                } else {
                    countColor = 0xDDDDDD; // Light gray: not started
                }

                int maxNameW = panelW - countW - 36;
                String displayName = font.plainSubstrByWidth(row.name(), Math.max(20, maxNameW));
                if (displayName.length() < row.name().length()) {
                    displayName += "...";
                }

                // Slightly brighter white for carried items (Tier 0-2), softer gray for uncarried (Tier 3)
                int nameColor = (row.priorityTier() <= 2) ? 0xFFFFFF : 0xAAAAAA;

                graphics.drawString(font, displayName, panelX + 26, y + 4, nameColor);
                graphics.drawString(font, countText, panelX + panelW - countW - 6, y + 4, countColor);
                y += rowHeight;
            }

            if (hasMore) {
                int extra = cachedRows.size() - MAX_VISIBLE_ROWS;
                graphics.drawString(font, "+ " + extra + " more (open Materials for all)", panelX + 6, y + 2, 0xAAAAAA);
            }
        }
    };
}