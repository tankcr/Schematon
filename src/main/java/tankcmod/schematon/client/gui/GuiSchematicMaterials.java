
package tankcmod.schematon.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
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

    public GuiSchematicMaterials(Screen parent) {
        super(Component.literal("Material List"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int bottom = this.height - 28;

        this.listWidget = new MaterialListWidget(
                this.minecraft, this.width, this.height, 32, this.height - 40, 24
        );
        this.addWidget(this.listWidget);

        this.addRenderableWidget(
                Button.builder(Component.literal("Done"),
                                b -> this.minecraft.setScreen(this.parent))
                        .bounds(this.width / 2 - 50, bottom, 100, 20)
                        .build()
        );

        calculateMaterials();
    }

    private void calculateMaterials() {
        VirtualSchematicLevel virtualLevel = SchematicPlacement.getActiveSchematic();
        if (virtualLevel == null) {
            return;
        }

        SchematicData data = virtualLevel.getSchematic();
        Vec3i size = data.getSize();

        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        int activeLayerY = SchematicPlacement.getLayerY();

        Map<Item, Integer> materialCounts = new HashMap<>();
        this.totalBlocks = 0;

        for (int y = 0; y < size.getY(); y++) {

            if (mode == SchematicPlacement.LayerMode.SINGLE_LAYER && y != activeLayerY) {
                continue;
            }

            if (mode == SchematicPlacement.LayerMode.ALL_BELOW && y > activeLayerY) {
                continue;
            }

            if (mode == SchematicPlacement.LayerMode.ALL_ABOVE && y < activeLayerY) {
                continue;
            }

            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {

                    BlockState state = data.getBlockState(x, y, z);

                    if (state.isAir()) {
                        continue;
                    }

                    Item item = state.getBlock().asItem();

                    if (item == Items.AIR) {
                        continue;
                    }

                    materialCounts.put(item, materialCounts.getOrDefault(item, 0) + 1);
                    this.totalBlocks++;
                }
            }
        }

        List<Map.Entry<Item, Integer>> sorted =
                new ArrayList<>(materialCounts.entrySet());

        sorted.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        // Sort alphabetically by item name
        sorted.sort(Comparator.comparing(
                e -> new ItemStack(e.getKey()).getHoverName().getString()
        ));

        for (Map.Entry<Item, Integer> entry : sorted) {
            this.listWidget.addMaterialEntry(
                    new MaterialEntry(
                            new ItemStack(entry.getKey()),
                            entry.getValue()
                    )
            );
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        this.listWidget.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        graphics.drawCenteredString(
                this.font,
                "Total Visible Blocks: " + this.totalBlocks,
                this.width / 2,
                20,
                0xAAAAAA
        );

        super.render(graphics, mouseX, mouseY, partialTick);
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
            return 260;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + 140;
        }
    }

    private class MaterialEntry extends ObjectSelectionList.Entry<MaterialEntry> {
        private final ItemStack stack;
        private final int count;
        private final Component name;

        public MaterialEntry(ItemStack stack, int count) {
            this.stack = stack;
            this.count = count;
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

            graphics.drawString(
                    GuiSchematicMaterials.this.font,
                    this.name,
                    left + 26,
                    top + 6,
                    0xFFFFFF
            );

            String countText = String.valueOf(this.count);
            int textWidth = GuiSchematicMaterials.this.font.width(countText);

            graphics.drawString(
                    GuiSchematicMaterials.this.font,
                    countText,
                    left + width - textWidth - 10,
                    top + 6,
                    0x55FF55
            );
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.count + " " + this.name.getString());
        }
    }
}
