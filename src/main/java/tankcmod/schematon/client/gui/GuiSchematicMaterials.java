package tankcmod.schematon.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import tankcmod.schematon.format.SchematicData;
import tankcmod.schematon.world.SchematicPlacement;
import tankcmod.schematon.world.VirtualSchematicLevel;

import java.util.*;

public class GuiSchematicMaterials extends Screen {
    private final Screen parent;
    private MaterialListWidget listWidget;

    private int totalBlocks = 0;
    private int remainingBlocks = 0;

    // Bottom-left panel geometry
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public GuiSchematicMaterials(Screen parent) {
        super(Component.literal("Materials"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int margin = 12;
        this.panelW = 240;
        this.panelH = Math.max(120, Math.min(260, (int) (this.height * 0.58f)));
        this.panelX = margin;
        this.panelY = this.height - this.panelH - margin;

        int listTop = this.panelY + 26;
        int listBottom = this.panelY + this.panelH - 6;

        this.listWidget = new MaterialListWidget(
                this.minecraft,
                this.panelW - 8,
                this.height,
                listTop,
                listBottom,
                22
        );
        this.listWidget.setLeftPos(this.panelX + 4);
        // Disable vanilla 1.20.1 full-width dirt background and header/footer bars
        this.listWidget.setRenderBackground(false);
        this.listWidget.setRenderTopAndBottom(false);
        this.addWidget(this.listWidget);

        // Compact Back button anchored right above the panel (mirrors Expand Menu in GuiSchematicControl)
        this.addRenderableWidget(
                Button.builder(Component.literal("Back"), b -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(this.parent);
                    }
                }).bounds(this.panelX, this.panelY - 24, 70, 20).build()
        );

        calculateMaterials();
    }

    private void calculateMaterials() {
        VirtualSchematicLevel virtualLevel = SchematicPlacement.getActiveSchematic();
        if (virtualLevel == null || this.minecraft == null || this.minecraft.level == null) {
            return;
        }

        SchematicData data = virtualLevel.getSchematic();
        Vec3i size = data.getSize();
        BlockPos origin = SchematicPlacement.getOrigin();

        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        int activeLayerY = SchematicPlacement.getLayerY();

        // int[0] = total required, int[1] = remaining to place
        Map<Item, int[]> materialCounts = new HashMap<>();
        this.totalBlocks = 0;
        this.remainingBlocks = 0;

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

                    BlockState worldState = this.minecraft.level.getBlockState(origin.offset(x, y, z));
                    boolean isPlaced = (schState.getBlock() == worldState.getBlock());

                    int[] counts = materialCounts.computeIfAbsent(item, k -> new int[2]);
                    counts[0]++; // total
                    this.totalBlocks++;

                    if (!isPlaced) {
                        counts[1]++; // remaining
                        this.remainingBlocks++;
                    }
                }
            }
        }

        List<Map.Entry<Item, int[]>> sorted =
                new ArrayList<>(materialCounts.entrySet());

// Sort: incomplete items first, then alphabetically by display name
        sorted.sort(
                Comparator.<Map.Entry<Item, int[]>, Boolean>comparing(e -> e.getValue()[1] == 0)
                        .thenComparing(e -> new ItemStack(e.getKey()).getHoverName().getString())
        );

        for (Map.Entry<Item, int[]> entry : sorted) {
            int total = entry.getValue()[0];
            int remaining = entry.getValue()[1];
            this.listWidget.addMaterialEntry(
                    new MaterialEntry(new ItemStack(entry.getKey()), remaining, total)
            );
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 70% transparent (30% alpha = 0x4D) backdrop strictly behind the bottom-left panel
        graphics.fill(
                this.panelX - 2,
                this.panelY - 2,
                this.panelX + this.panelW + 2,
                this.panelY + this.panelH + 2,
                0x4D000000
        );

        // Panel header: Title on left, Remaining / Total on right
        graphics.drawString(this.font, this.title, this.panelX + 6, this.panelY + 6, 0xFFFFFF);

        String summary = "Left: " + this.remainingBlocks + " / " + this.totalBlocks;
        int summaryW = this.font.width(summary);
        int summaryColor = (this.remainingBlocks == 0 && this.totalBlocks > 0) ? 0x55FF55 : 0xCCCCCC;
        graphics.drawString(
                this.font,
                summary,
                this.panelX + this.panelW - summaryW - 6,
                this.panelY + 6,
                summaryColor
        );

        // Subtle divider line under header
        graphics.fill(
                this.panelX + 4,
                this.panelY + 20,
                this.panelX + this.panelW - 4,
                this.panelY + 21,
                0x55FFFFFF
        );

        this.listWidget.render(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private class MaterialListWidget extends ObjectSelectionList<MaterialEntry> {
        public MaterialListWidget(
                Minecraft mc, int width, int height,
                int y0, int y1, int itemHeight
        ) {
            super(mc, width, height, y0, y1, itemHeight);
        }

        public void addMaterialEntry(MaterialEntry entry) {
            this.addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return this.width - 12;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.x1 - 6;
        }
    }

    private class MaterialEntry extends ObjectSelectionList.Entry<MaterialEntry> {
        private final ItemStack stack;
        private final int remaining;
        private final int total;
        private final Component name;

        public MaterialEntry(ItemStack stack, int remaining, int total) {
            this.stack = stack;
            this.remaining = remaining;
            this.total = total;
            this.name = stack.getHoverName();
        }

        @Override
        public void render(
                GuiGraphics graphics,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean isMouseOver,
                float partialTick
        ) {
            graphics.renderFakeItem(this.stack, left + 2, top + 2);

            String countText = this.remaining + "/" + this.total;
            int countWidth = GuiSchematicMaterials.this.font.width(countText);

            int countColor;
            if (this.remaining == 0) {
                countColor = 0x55FF55; // Green: complete
            } else if (this.remaining < this.total) {
                countColor = 0xFFFF55; // Yellow: in progress
            } else {
                countColor = 0xDDDDDD; // Light gray: not started
            }

            // Clip long item names so they never overlap the count text
            int maxNameWidth = width - countWidth - 36;
            String displayName = GuiSchematicMaterials.this.font.plainSubstrByWidth(this.name.getString(), Math.max(20, maxNameWidth));
            if (displayName.length() < this.name.getString().length()) {
                displayName = displayName + "...";
            }

            int nameColor = (this.remaining == 0) ? 0x888888 : 0xFFFFFF;
            graphics.drawString(
                    GuiSchematicMaterials.this.font,
                    displayName,
                    left + 22,
                    top + 6,
                    nameColor
            );

            graphics.drawString(
                    GuiSchematicMaterials.this.font,
                    countText,
                    left + width - countWidth - 6,
                    top + 6,
                    countColor
            );
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.remaining + " of " + this.total + " remaining for " + this.name.getString());
        }
    }
}