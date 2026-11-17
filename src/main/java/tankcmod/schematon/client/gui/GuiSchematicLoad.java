package tankcmod.schematon.client.gui;

import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import tankcmod.schematon.format.SchematicData;
import tankcmod.schematon.world.SchematicPlacement;
import tankcmod.schematon.world.VirtualSchematicLevel;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

public class GuiSchematicLoad extends Screen {
    private static final Logger LOGGER = LogUtils.getLogger();

    private File schematicsDir;
    private SchematicListWidget listWidget;
    private Button loadButton;
    private Component statusMessage = Component.empty();
    private int statusColor = 0xAAAAAA;

    public GuiSchematicLoad() {
        super(Component.literal("Select Schematic"));
    }

    @Override
    protected void init() {
        // Resolve <gameDir>/schematics (e.g. run/schematics in dev, .minecraft/schematics in prod)
        this.schematicsDir = new File(Minecraft.getInstance().gameDirectory, "schematics");
        if (!this.schematicsDir.exists() && !this.schematicsDir.mkdirs()) {
            LOGGER.warn("Failed to create schematics directory at {}", this.schematicsDir.getAbsolutePath());
        }

        int centerX = this.width / 2;
        int bottom = this.height - 28;

        // Scrollable classic list between top header (y=32) and bottom button bar (height - 40)
        this.listWidget = new SchematicListWidget(this.minecraft, this.width, this.height, 32, this.height - 40, 18);
        this.addWidget(this.listWidget);

        // Classic Schematica 3-button bottom bar
        this.addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> openSchematicsFolder())
                .bounds(centerX - 154, bottom, 100, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.minecraft.setScreen(null))
                .bounds(centerX - 50, bottom, 100, 20)
                .build());

        this.loadButton = Button.builder(Component.literal("Load"), b -> loadSelectedSchematic())
                .bounds(centerX + 54, bottom, 100, 20)
                .build();
        this.loadButton.active = false;
        this.addRenderableWidget(this.loadButton);

        refreshFileList();
    }

    private void refreshFileList() {
        this.listWidget.clearEntries();
        File[] files = this.schematicsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".nbt"));

        if (files == null || files.length == 0) {
            this.statusMessage = Component.literal("No .nbt schematics found in /schematics");
            this.statusColor = 0xFFAA00;
            this.loadButton.active = false;
            return;
        }

        Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : files) {
            this.listWidget.addSchematicEntry(file);
        }

        this.statusMessage = Component.literal(files.length + " schematic(s) available");
        this.statusColor = 0xAAAAAA;
    }

    private void openSchematicsFolder() {
        if (this.schematicsDir != null) {
            Util.getPlatform().openFile(this.schematicsDir);
        }
    }

    private void loadSelectedSchematic() {
        SchematicListWidget.SchematicEntry selected = this.listWidget.getSelected();
        if (selected == null || this.minecraft == null || this.minecraft.level == null || this.minecraft.player == null) {
            return;
        }

        File file = selected.getFile();
        try {
            SchematicData data = SchematicData.loadFromNbtFile(file, this.minecraft.level);
            VirtualSchematicLevel virtualLevel = new VirtualSchematicLevel(data, this.minecraft.level);

            // Anchor at the player's current block position (classic Schematica behavior)
            BlockPos playerPos = this.minecraft.player.blockPosition();
            SchematicPlacement.setActiveSchematic(virtualLevel);
            SchematicPlacement.setOrigin(playerPos);

            if (!data.getMissingModBlocks().isEmpty()) {
                this.minecraft.player.displayClientMessage(
                        Component.literal("§e[Schematon] Loaded " + data.getName() + " with " + data.getMissingModBlocks().size() + " missing mod block type(s)."),
                        false
                );
            } else {
                this.minecraft.player.displayClientMessage(
                        Component.literal("§a[Schematon] Loaded schematic: " + data.getName() + " (" + data.getSize().toShortString() + ")"),
                        false
                );
            }

            // Close GUI immediately on successful load, matching 1.7.10 Schematica
            this.minecraft.setScreen(null);
        } catch (IOException e) {
            LOGGER.error("Failed to load schematic file: {}", file.getName(), e);
            this.statusMessage = Component.literal("Error loading file: " + e.getMessage());
            this.statusColor = 0xFF5555;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        this.listWidget.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
        if (!this.statusMessage.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.statusMessage, this.width / 2, 20, this.statusColor);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Scrollable file list widget matching 1.7.10 GuiSlot selection behavior (including double-click to load).
     */
    private class SchematicListWidget extends ObjectSelectionList<SchematicListWidget.SchematicEntry> {
        public SchematicListWidget(Minecraft mc, int width, int height, int y0, int y1, int itemHeight) {
            super(mc, width, height, y0, y1, itemHeight);
        }

        public void clearEntries() {
            super.clearEntries();
        }

        public void addSchematicEntry(File file) {
            this.addEntry(new SchematicEntry(file));
        }

        @Override
        public void setSelected(@Nullable SchematicEntry entry) {
            super.setSelected(entry);
            GuiSchematicLoad.this.loadButton.active = (entry != null);
        }

        @Override
        public int getRowWidth() {
            return 260;
        }

        @Override
        protected int getScrollbarPosition() {
            return this.width / 2 + 140;
        }

        private class SchematicEntry extends ObjectSelectionList.Entry<SchematicEntry> {
            private final File file;
            private long lastClickTime = 0L;

            public SchematicEntry(File file) {
                this.file = file;
            }

            public File getFile() {
                return this.file;
            }

            @Override
            public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean isMouseOver, float partialTick) {
                String displayName = this.file.getName();
                int textColor = (SchematicListWidget.this.getSelected() == this) ? 0xFFFF55 : 0xFFFFFF;
                graphics.drawString(GuiSchematicLoad.this.font, displayName, left + 5, top + 3, textColor);
            }

            @Override
            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    SchematicListWidget.this.setSelected(this);
                    long now = Util.getMillis();
                    if (now - this.lastClickTime < 250L) {
                        GuiSchematicLoad.this.loadSelectedSchematic();
                    }
                    this.lastClickTime = now;
                    return true;
                }
                return false;
            }

            @Override
            public Component getNarration() {
                return Component.literal(this.file.getName());
            }
        }
    }
}