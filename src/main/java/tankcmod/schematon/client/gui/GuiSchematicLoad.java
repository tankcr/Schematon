package tankcmod.schematon.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class GuiSchematicLoad extends Screen {
    public GuiSchematicLoad() {
        super(Component.literal("Select Schematic"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int bottom = this.height - 30;

        // Classic Schematica Load layout placeholders
        this.addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> {}).bounds(centerX - 154, bottom, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.minecraft.setScreen(null)).bounds(centerX - 50, bottom, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Load"), b -> {}).bounds(centerX + 54, bottom, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
