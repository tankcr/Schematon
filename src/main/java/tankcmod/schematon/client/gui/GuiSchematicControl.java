package tankcmod.schematon.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import tankcmod.schematon.world.SchematicPlacement;

public class GuiSchematicControl extends Screen {
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;

    // Layer slice controls
    private Button layerModeBtn;
    private Button layerMinusBtn;
    private Button layerPlusBtn;
    private EditBox layerBox;

    // Guard flag prevents EditBox responders from re-triggering during programmatic sync
    private boolean updatingBoxes = false;

    public GuiSchematicControl() {
        super(Component.literal("Schematic Control"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        int rowX = centerX - 135;
        int startY = centerY - 55;
        int rowSpacing = 24;

        // --- LEFT COLUMN: Coordinates ---
        this.xBox = createAxisRow("X:", rowX, startY, 0);
        this.yBox = createAxisRow("Y:", rowX, startY + rowSpacing, 1);
        this.zBox = createAxisRow("Z:", rowX, startY + (rowSpacing * 2), 2);

        this.addRenderableWidget(Button.builder(Component.literal("Move Here"), b -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                SchematicPlacement.setOrigin(this.minecraft.player.blockPosition());
                syncBoxesFromPlacement();
            }
        }).bounds(rowX + 18, startY + (rowSpacing * 3), 114, 20).build());


        // --- RIGHT COLUMN: Utilities & Slicing ---
        int utilX = centerX + 35;

        // Row 1: Unload
        this.addRenderableWidget(Button.builder(Component.literal("Unload"), b -> {
            SchematicPlacement.setActiveSchematic(null);
            this.minecraft.setScreen(null);
        }).bounds(utilX, startY, 85, 20).build());

        // Row 2: Layer Mode Toggle
        this.layerModeBtn = this.addRenderableWidget(Button.builder(Component.literal("Layers: ALL"), b -> {
            cycleLayerMode();
        }).bounds(utilX, startY + rowSpacing, 85, 20).build());

        // Row 3: Layer Selection (-, Box, +) - 85px total width
        this.layerMinusBtn = this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            nudgeLayer(hasShiftDown() ? -5 : -1);
        }).bounds(utilX, startY + (rowSpacing * 2), 20, 20).build());

        this.layerBox = new EditBox(this.font, utilX + 22, startY + (rowSpacing * 2) + 1, 41, 18, Component.literal("Layer"));
        this.layerBox.setMaxLength(4);
        this.layerBox.setFilter(text -> text.matches("-?\\d*"));
        this.layerBox.setResponder(text -> {
            if (this.updatingBoxes || text.isEmpty() || text.equals("-")) return;
            try {
                setLayerSafe(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {}
        });
        this.addRenderableWidget(this.layerBox);

        this.layerPlusBtn = this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            nudgeLayer(hasShiftDown() ? 5 : 1);
        }).bounds(utilX + 65, startY + (rowSpacing * 2), 20, 20).build());

        // Row 4: Materials
        Button materialsBtn = Button.builder(Component.literal("Materials"), b -> {})
                .bounds(utilX, startY + (rowSpacing * 3), 85, 20).build();
        materialsBtn.active = false;
        this.addRenderableWidget(materialsBtn);

        // Row 5: Printer
        Button printerBtn = Button.builder(Component.literal("Printer: OFF"), b -> {})
                .bounds(utilX, startY + (rowSpacing * 4), 85, 20).build();
        printerBtn.active = false;
        this.addRenderableWidget(printerBtn);


        // --- BOTTOM ---
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.minecraft.setScreen(null))
                .bounds(centerX - 50, this.height - 30, 100, 20).build());

        syncBoxesFromPlacement();
        updateLayerControls();
    }

    private EditBox createAxisRow(String label, int x, int y, int axisIndex) {
        int btnWidth = 20;
        int boxWidth = 66;
        int controlStart = x + 18;

        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            nudgeAxis(axisIndex, hasShiftDown() ? -10 : -1);
        }).bounds(controlStart, y, btnWidth, 20).build());

        EditBox box = new EditBox(this.font, controlStart + btnWidth + 4, y + 1, boxWidth, 18, Component.literal(label));
        box.setMaxLength(10);
        box.setFilter(text -> text.matches("-?\\d*"));
        box.setResponder(text -> {
            if (this.updatingBoxes) return;
            applyTypedCoordinate(axisIndex, text);
        });
        this.addRenderableWidget(box);

        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            nudgeAxis(axisIndex, hasShiftDown() ? 10 : 1);
        }).bounds(controlStart + btnWidth + boxWidth + 8, y, btnWidth, 20).build());

        return box;
    }

    private void nudgeAxis(int axisIndex, int delta) {
        switch (axisIndex) {
            case 0 -> SchematicPlacement.move(delta, 0, 0);
            case 1 -> SchematicPlacement.move(0, delta, 0);
            case 2 -> SchematicPlacement.move(0, 0, delta);
        }
        syncBoxesFromPlacement();
    }

    private void applyTypedCoordinate(int axisIndex, String text) {
        if (text.isEmpty() || text.equals("-")) return;
        try {
            int parsed = Integer.parseInt(text);
            BlockPos current = SchematicPlacement.getOrigin();
            BlockPos updated = switch (axisIndex) {
                case 0 -> new BlockPos(parsed, current.getY(), current.getZ());
                case 1 -> new BlockPos(current.getX(), parsed, current.getZ());
                case 2 -> new BlockPos(current.getX(), current.getY(), parsed);
                default -> current;
            };
            SchematicPlacement.setOrigin(updated);
        } catch (NumberFormatException ignored) {}
    }

    // --- Slicing Logic ---

    private void cycleLayerMode() {
        SchematicPlacement.LayerMode current = SchematicPlacement.getLayerMode();
        SchematicPlacement.LayerMode next = switch (current) {
            case ALL -> SchematicPlacement.LayerMode.ALL_BELOW;
            case ALL_BELOW -> SchematicPlacement.LayerMode.SINGLE_LAYER;
            case SINGLE_LAYER -> SchematicPlacement.LayerMode.ALL;
        };
        SchematicPlacement.setLayerMode(next);
        updateLayerControls();
    }

    private void updateLayerControls() {
        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        boolean slicingActive = (mode != SchematicPlacement.LayerMode.ALL);

        // Grey out / Disable layer Y selection if mode is ALL
        this.layerMinusBtn.active = slicingActive;
        this.layerPlusBtn.active = slicingActive;
        this.layerBox.setEditable(slicingActive);

        String modeLabel = switch (mode) {
            case ALL -> "Layers: ALL";
            case ALL_BELOW -> "Layers: BELOW";
            case SINGLE_LAYER -> "Layers: SINGLE";
        };
        this.layerModeBtn.setMessage(Component.literal(modeLabel));
    }

    private void nudgeLayer(int delta) {
        setLayerSafe(SchematicPlacement.getLayerY() + delta);
    }

    private void setLayerSafe(int desiredY) {
        var active = SchematicPlacement.getActiveSchematic();
        int max = active != null ? active.getSchematic().getSize().getY() - 1 : 255;
        int clamped = Math.max(0, Math.min(desiredY, max));
        SchematicPlacement.setLayerY(clamped);
        syncBoxesFromPlacement();
    }

    private void syncBoxesFromPlacement() {
        BlockPos origin = SchematicPlacement.getOrigin();
        this.updatingBoxes = true;
        try {
            this.xBox.setValue(String.valueOf(origin.getX()));
            this.yBox.setValue(String.valueOf(origin.getY()));
            this.zBox.setValue(String.valueOf(origin.getZ()));
            this.layerBox.setValue(String.valueOf(SchematicPlacement.getLayerY()));
        } finally {
            this.updatingBoxes = false;
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.xBox.tick();
        this.yBox.tick();
        this.zBox.tick();
        this.layerBox.tick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);

        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int rowX = centerX - 135;
        int startY = centerY - 55;
        int rowSpacing = 24;

        graphics.drawCenteredString(this.font, this.title, centerX, 12, 0xFFFFFF);

        var active = SchematicPlacement.getActiveSchematic();
        if (active != null) {
            String info = active.getSchematic().getName() + " (" + active.getSchematic().getSize().toShortString() + ")";
            graphics.drawCenteredString(this.font, info, centerX, 25, 0x55FFFF);
        } else {
            graphics.drawCenteredString(this.font, "No schematic loaded", centerX, 25, 0xFF5555);
        }

        // Axis labels
        graphics.drawString(this.font, "X:", rowX, startY + 6, 0xFF5555);
        graphics.drawString(this.font, "Y:", rowX, startY + rowSpacing + 6, 0x55FF55);
        graphics.drawString(this.font, "Z:", rowX, startY + (rowSpacing * 2) + 6, 0x5555FF);

        graphics.drawCenteredString(this.font, "Tip: Hold Shift + click [-]/[+] to move by 10 (or 5) blocks", centerX, this.height - 44, 0xAAAAAA);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}