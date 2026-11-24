package tankcmod.schematon.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import tankcmod.schematon.client.create.SchematicSelection;
import tankcmod.schematon.format.SchematicExporter;
import tankcmod.schematon.network.ExportSchematicRequestPacket;
import tankcmod.schematon.network.SchematonNetwork;

import java.io.File;

public class GuiSchematicCreate extends Screen {
    // Pos 1 (Blue) coordinate inputs
    private EditBox p1XBox;
    private EditBox p1YBox;
    private EditBox p1ZBox;

    // Pos 2 (Red) coordinate inputs
    private EditBox p2XBox;
    private EditBox p2YBox;
    private EditBox p2ZBox;

    // Right-panel controls
    private EditBox nameBox;
    private Button clickSelectBtn;
    private Button entitiesBtn;
    private Button nextCornerBtn;
    private Button ignoreAirBtn;
    private Button ignoreContentsBtn;

    private static boolean includeEntities = false;
    private static boolean ignoreAir = true;
    private static boolean ignoreContents = false;
    private static String lastSchematicName = "my_schematic";

    // Cached panel positions for background/labels
    private int panel1X;
    private int panel2X;
    private int panelsY;
    private int panelW;
    private int panelH;

    // Guard flag prevents EditBox responders from re-triggering during programmatic sync
    private boolean updatingBoxes = false;

    public GuiSchematicCreate() {
        super(Component.literal("Schematic Creation"));
    }

    @Override
    protected void init() {
        if (this.minecraft != null && this.minecraft.player != null) {
            SchematicSelection.ensureInitialized(this.minecraft.player.blockPosition());
        }

        int margin = 12;
        int rowSpacing = 24;

        // --- BOTTOM-LEFT: Two side-by-side Per-Corner Coordinate Panels (Pos 1 Blue & Pos 2 Red) ---
        this.panelW = 148;
        this.panelH = (rowSpacing * 4) + 22; // Header + 3 axis rows + Set Here/Pick row
        this.panel1X = margin;
        this.panel2X = margin + this.panelW + 8;
        this.panelsY = this.height - this.panelH - margin;

        int p1StartY = this.panelsY + 18;
        this.p1XBox = createCornerAxisRow(true, "P1 X:", this.panel1X + 8, p1StartY, 0);
        this.p1YBox = createCornerAxisRow(true, "P1 Y:", this.panel1X + 8, p1StartY + rowSpacing, 1);
        this.p1ZBox = createCornerAxisRow(true, "P1 Z:", this.panel1X + 8, p1StartY + (rowSpacing * 2), 2);

        this.addRenderableWidget(Button.builder(Component.literal("Set Here"), b -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                SchematicSelection.setPos1(this.minecraft.player.blockPosition());
                syncBoxesFromSelection();
            }
        }).bounds(this.panel1X + 8, p1StartY + (rowSpacing * 3), 64, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Pick P1"), b -> {
            SchematicSelection.setNextClickSetsPos1(true);
            SchematicSelection.setClickToSelectEnabled(true);
            updateToggleLabels();
        }).bounds(this.panel1X + 76, p1StartY + (rowSpacing * 3), 64, 20).build());

        int p2StartY = this.panelsY + 18;
        this.p2XBox = createCornerAxisRow(false, "P2 X:", this.panel2X + 8, p2StartY, 0);
        this.p2YBox = createCornerAxisRow(false, "P2 Y:", this.panel2X + 8, p2StartY + rowSpacing, 1);
        this.p2ZBox = createCornerAxisRow(false, "P2 Z:", this.panel2X + 8, p2StartY + (rowSpacing * 2), 2);

        this.addRenderableWidget(Button.builder(Component.literal("Set Here"), b -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                SchematicSelection.setPos2(this.minecraft.player.blockPosition());
                syncBoxesFromSelection();
            }
        }).bounds(this.panel2X + 8, p2StartY + (rowSpacing * 3), 64, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Pick P2"), b -> {
            SchematicSelection.setNextClickSetsPos1(false);
            SchematicSelection.setClickToSelectEnabled(true);
            updateToggleLabels();
        }).bounds(this.panel2X + 76, p2StartY + (rowSpacing * 3), 64, 20).build());

        // --- RIGHT EDGE: Selection Options, Filename & Save .NBT ---
        int utilW = 116;
        int utilX = this.width - utilW - margin;
        int utilStartY = Math.max(38, (this.height - (rowSpacing * 8)) / 2);

        this.clickSelectBtn = this.addRenderableWidget(Button.builder(
                Component.literal(getClickSelectText()),
                b -> {
                    SchematicSelection.toggleClickToSelect();
                    updateToggleLabels();
                }
        ).bounds(utilX, utilStartY, utilW, 20).build());

        this.nextCornerBtn = this.addRenderableWidget(Button.builder(
                Component.literal(getNextCornerText()),
                b -> {
                    SchematicSelection.setNextClickSetsPos1(!SchematicSelection.isNextClickSetsPos1());
                    updateToggleLabels();
                }
        ).bounds(utilX, utilStartY + rowSpacing, utilW, 20).build());

        this.entitiesBtn = this.addRenderableWidget(Button.builder(
                Component.literal(getEntitiesText()),
                b -> {
                    includeEntities = !includeEntities;
                    updateToggleLabels();
                }
        ).bounds(utilX, utilStartY + (rowSpacing * 2), utilW, 20).build());

        this.ignoreAirBtn = this.addRenderableWidget(Button.builder(
                Component.literal(getIgnoreAirText()),
                b -> {
                    ignoreAir = !ignoreAir;
                    updateToggleLabels();
                }
        ).bounds(utilX, utilStartY + (rowSpacing * 3), utilW, 20).build());

        this.ignoreContentsBtn = this.addRenderableWidget(Button.builder(
                Component.literal(getIgnoreContentsText()),
                b -> {
                    ignoreContents = !ignoreContents;
                    updateToggleLabels();
                }
        ).bounds(utilX, utilStartY + (rowSpacing * 4), utilW, 20).build());

        this.nameBox = new EditBox(this.font, utilX, utilStartY + (rowSpacing * 5) + 1, utilW, 18, Component.literal("Schematic Name"));
        this.nameBox.setMaxLength(64);
        this.nameBox.setValue(lastSchematicName);
        this.nameBox.setResponder(val -> lastSchematicName = val);
        this.addRenderableWidget(this.nameBox);

        this.addRenderableWidget(Button.builder(Component.literal("Save .NBT"), b -> handleSaveSchematic())
                .bounds(utilX, utilStartY + (rowSpacing * 6), utilW, 20)
                .build());

        this.addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
            SchematicSelection.clear();
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
        }).bounds(utilX, utilStartY + (rowSpacing * 7), 56, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
        }).bounds(utilX + 60, utilStartY + (rowSpacing * 7), 56, 20).build());

        syncBoxesFromSelection();
        updateToggleLabels();
    }

    private EditBox createCornerAxisRow(boolean isPos1, String label, int x, int y, int axisIndex) {
        int btnWidth = 20;
        int boxWidth = 66;
        int controlStart = x + 18;

        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            nudgeCornerAxis(isPos1, axisIndex, hasShiftDown() ? -10 : -1);
        }).bounds(controlStart, y, btnWidth, 20).build());

        EditBox box = new EditBox(this.font, controlStart + btnWidth + 4, y + 1, boxWidth, 18, Component.literal(label));
        box.setMaxLength(10);
        box.setFilter(text -> text.matches("-?\\d*"));
        box.setResponder(text -> {
            if (this.updatingBoxes) return;
            applyTypedCornerCoordinate(isPos1, axisIndex, text);
        });
        this.addRenderableWidget(box);

        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            nudgeCornerAxis(isPos1, axisIndex, hasShiftDown() ? 10 : 1);
        }).bounds(controlStart + btnWidth + boxWidth + 8, y, btnWidth, 20).build());

        return box;
    }

    private void nudgeCornerAxis(boolean isPos1, int axisIndex, int delta) {
        int dx = (axisIndex == 0) ? delta : 0;
        int dy = (axisIndex == 1) ? delta : 0;
        int dz = (axisIndex == 2) ? delta : 0;

        if (isPos1) {
            SchematicSelection.movePos1(dx, dy, dz);
        } else {
            SchematicSelection.movePos2(dx, dy, dz);
        }
        syncBoxesFromSelection();
    }

    private void applyTypedCornerCoordinate(boolean isPos1, int axisIndex, String text) {
        if (text.isEmpty() || text.equals("-")) return;
        try {
            int parsed = Integer.parseInt(text);
            BlockPos current = isPos1 ? SchematicSelection.getPos1() : SchematicSelection.getPos2();
            if (current == null) return;

            BlockPos updated = switch (axisIndex) {
                case 0 -> new BlockPos(parsed, current.getY(), current.getZ());
                case 1 -> new BlockPos(current.getX(), parsed, current.getZ());
                case 2 -> new BlockPos(current.getX(), current.getY(), parsed);
                default -> current;
            };

            if (isPos1) {
                SchematicSelection.setPos1(updated);
            } else {
                SchematicSelection.setPos2(updated);
            }
        } catch (NumberFormatException ignored) {}
    }

    private void syncBoxesFromSelection() {
        BlockPos p1 = SchematicSelection.getPos1();
        BlockPos p2 = SchematicSelection.getPos2();

        this.updatingBoxes = true;
        try {
            if (p1 != null) {
                this.p1XBox.setValue(String.valueOf(p1.getX()));
                this.p1YBox.setValue(String.valueOf(p1.getY()));
                this.p1ZBox.setValue(String.valueOf(p1.getZ()));
            }
            if (p2 != null) {
                this.p2XBox.setValue(String.valueOf(p2.getX()));
                this.p2YBox.setValue(String.valueOf(p2.getY()));
                this.p2ZBox.setValue(String.valueOf(p2.getZ()));
            }
        } finally {
            this.updatingBoxes = false;
        }
    }

    private void updateToggleLabels() {
        if (this.clickSelectBtn != null) {
            this.clickSelectBtn.setMessage(Component.literal(getClickSelectText()));
        }
        if (this.nextCornerBtn != null) {
            this.nextCornerBtn.setMessage(Component.literal(getNextCornerText()));
        }
        if (this.entitiesBtn != null) {
            this.entitiesBtn.setMessage(Component.literal(getEntitiesText()));
        }
        if (this.ignoreAirBtn != null) {
            this.ignoreAirBtn.setMessage(Component.literal(getIgnoreAirText()));
        }
        if (this.ignoreContentsBtn != null) {
            this.ignoreContentsBtn.setMessage(Component.literal(getIgnoreContentsText()));
        }
    }

    private String getClickSelectText() {
        return SchematicSelection.isClickToSelectEnabled() ? "Right-Click: ON" : "Right-Click: OFF";
    }

    private String getNextCornerText() {
        return SchematicSelection.isNextClickSetsPos1() ? "Next Click: Pos 1" : "Next Click: Pos 2";
    }

    private String getEntitiesText() {
        return includeEntities ? "Entities: ON" : "Entities: OFF";
    }

    private String getIgnoreAirText() {
        return ignoreAir ? "Ignore Air: ON" : "Ignore Air: OFF";
    }

    private String getIgnoreContentsText() {
        return ignoreContents ? "Ignore Items: ON" : "Ignore Items: OFF";
    }

    private void handleSaveSchematic() {
        if (this.minecraft == null || this.minecraft.player == null || this.minecraft.level == null) return;
        BlockPos min = SchematicSelection.getMinPos();
        BlockPos max = SchematicSelection.getMaxPos();
        if (min == null || max == null) {
            this.minecraft.player.displayClientMessage(
                    Component.literal("§c[Schematon] Set both Pos 1 and Pos 2 before saving."),
                    true
            );
            return;
        }

        File schematicsDir = new File(this.minecraft.gameDirectory, "schematics");
        String rawName = this.nameBox != null ? this.nameBox.getValue().trim() : "";
        String fileName = SchematicExporter.sanitizeNbtFileName(rawName);
        boolean captureEntities = includeEntities;
        boolean skipAir = ignoreAir;
        boolean skipContents = ignoreContents;

        MinecraftServer singleplayerServer = this.minecraft.getSingleplayerServer();
        var dimKey = this.minecraft.player.level().dimension();
        var clientLevel = this.minecraft.level;
        var player = this.minecraft.player;

        if (singleplayerServer != null) {
            singleplayerServer.execute(() -> {
                ServerLevel serverLevel = singleplayerServer.getLevel(dimKey);
                Level targetLevel = (serverLevel != null) ? serverLevel : clientLevel;
                SchematicExporter.ExportResult result = SchematicExporter.exportToNbtFile(
                        targetLevel, min, max, schematicsDir, fileName, captureEntities, skipAir, skipContents
                );
                this.minecraft.execute(() -> player.displayClientMessage(
                        Component.literal((result.success() ? "§a[Schematon] " : "§c[Schematon] ") + result.message()),
                        false
                ));
            });
            this.minecraft.setScreen(null);
            return;
        }

        Connection connection = (this.minecraft.getConnection() != null)
                ? this.minecraft.getConnection().getConnection()
                : null;
        if (SchematonNetwork.isRemoteChannelPresent(connection)) {
            ExportSchematicRequestPacket request = new ExportSchematicRequestPacket(
                    min, max, fileName, captureEntities, skipAir, skipContents
            );
            if (!SchematonNetwork.sendToServer(request)) {
                player.displayClientMessage(
                        Component.literal("§c[Schematon] Failed sending schematic export request to server."),
                        false
                );
                return;
            }
            this.minecraft.setScreen(null);
            return;
        }

        SchematicExporter.ExportResult result = SchematicExporter.exportToNbtFile(
                clientLevel, min, max, schematicsDir, fileName, captureEntities, skipAir, skipContents
        );
        player.displayClientMessage(
                Component.literal((result.success() ? "§a[Schematon] " : "§c[Schematon] ") + result.message()),
                false
        );
        this.minecraft.setScreen(null);
    }

    @Override
    public void tick() {
        super.tick();
        this.p1XBox.tick();
        this.p1YBox.tick();
        this.p1ZBox.tick();
        this.p2XBox.tick();
        this.p2YBox.tick();
        this.p2ZBox.tick();
        this.nameBox.tick();

        // Keep boxes & "Next Click" button synced if corners changed via in-world right-click
        if (!this.p1XBox.isFocused() && !this.p1YBox.isFocused() && !this.p1ZBox.isFocused()
                && !this.p2XBox.isFocused() && !this.p2YBox.isFocused() && !this.p2ZBox.isFocused()) {
            syncBoxesFromSelection();
            updateToggleLabels();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int centerX = this.width / 2;
        int rowSpacing = 24;

        graphics.drawCenteredString(this.font, this.title, centerX, 12, 0xFFFFFF);

        Vec3i size = SchematicSelection.getSize();
        if (size != null) {
            long totalBlocks = (long) size.getX() * size.getY() * size.getZ();
            String dimText = "Size: " + size.getX() + " x " + size.getY() + " x " + size.getZ()
                    + " (" + totalBlocks + " blocks)";
            graphics.drawCenteredString(this.font, dimText, centerX, 25, 0x55FFFF);
        }

        // Translucent backing boxes for Pos 1 (Blue) and Pos 2 (Red) panels
        graphics.fill(this.panel1X, this.panelsY, this.panel1X + this.panelW, this.panelsY + this.panelH, 0x66000000);
        graphics.fill(this.panel2X, this.panelsY, this.panel2X + this.panelW, this.panelsY + this.panelH, 0x66000000);

        graphics.drawString(this.font, "Pos 1 (Blue)", this.panel1X + 8, this.panelsY + 5, 0x55AAFF);
        graphics.drawString(this.font, "Pos 2 (Red)", this.panel2X + 8, this.panelsY + 5, 0xFF5555);

        int p1StartY = this.panelsY + 18;
        graphics.drawString(this.font, "X:", this.panel1X + 8, p1StartY + 6, 0xFF5555);
        graphics.drawString(this.font, "Y:", this.panel1X + 8, p1StartY + rowSpacing + 6, 0x55FF55);
        graphics.drawString(this.font, "Z:", this.panel1X + 8, p1StartY + (rowSpacing * 2) + 6, 0x5555FF);

        int p2StartY = this.panelsY + 18;
        graphics.drawString(this.font, "X:", this.panel2X + 8, p2StartY + 6, 0xFF5555);
        graphics.drawString(this.font, "Y:", this.panel2X + 8, p2StartY + rowSpacing + 6, 0x55FF55);
        graphics.drawString(this.font, "Z:", this.panel2X + 8, p2StartY + (rowSpacing * 2) + 6, 0x5555FF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}