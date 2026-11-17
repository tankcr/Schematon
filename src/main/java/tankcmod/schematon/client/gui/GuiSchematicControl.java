package tankcmod.schematon.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import tankcmod.schematon.world.SchematicPlacement;

public class GuiSchematicControl extends Screen {
    public GuiSchematicControl() {
        super(Component.literal("Schematic Control"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        // X/Y/Z Coordinate Nudging (Left side)
        this.addRenderableWidget(Button.builder(Component.literal("X -"), b -> SchematicPlacement.move(-1, 0, 0)).bounds(centerX - 100, centerY - 40, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("X +"), b -> SchematicPlacement.move(1, 0, 0)).bounds(centerX - 55, centerY - 40, 40, 20).build());
        
        this.addRenderableWidget(Button.builder(Component.literal("Y -"), b -> SchematicPlacement.move(0, -1, 0)).bounds(centerX - 100, centerY - 15, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Y +"), b -> SchematicPlacement.move(0, 1, 0)).bounds(centerX - 55, centerY - 15, 40, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Z -"), b -> SchematicPlacement.move(0, 0, -1)).bounds(centerX - 100, centerY + 10, 40, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Z +"), b -> SchematicPlacement.move(0, 0, 1)).bounds(centerX - 55, centerY + 10, 40, 20).build());

        // Utilities (Right side)
        this.addRenderableWidget(Button.builder(Component.literal("Unload"), b -> SchematicPlacement.setActiveSchematic(null)).bounds(centerX + 15, centerY - 40, 80, 20).build());
        
        Button materialsBtn = Button.builder(Component.literal("Materials"), b -> {}).bounds(centerX + 15, centerY - 15, 80, 20).build();
        materialsBtn.active = false; // Disabled for now
        this.addRenderableWidget(materialsBtn);
        
        Button printerBtn = Button.builder(Component.literal("Printer: OFF"), b -> {}).bounds(centerX + 15, centerY + 10, 80, 20).build();
        printerBtn.active = false; // Disabled for now
        this.addRenderableWidget(printerBtn);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
