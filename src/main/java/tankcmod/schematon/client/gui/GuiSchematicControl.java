package tankcmod.schematon.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import tankcmod.schematon.network.PasteSchematicPacket;
import tankcmod.schematon.network.SchematonNetwork;
import tankcmod.schematon.network.PasteSchematicChunkPacket;
import java.util.UUID;
import tankcmod.schematon.world.SchematicPaster;
import tankcmod.schematon.world.SchematicPlacement;
import tankcmod.schematon.world.VirtualSchematicLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GuiSchematicControl extends Screen {
    private EditBox xBox;
    private EditBox yBox;
    private EditBox zBox;

    // Layer slice controls
    private Button layerModeBtn;
    private Button layerMinusBtn;
    private Button layerPlusBtn;
    private EditBox layerBox;

    // Compact / Auto-collapse state
    private boolean compactMoveMode = false;
    private Button expandBtn;
    private final List<AbstractWidget> fullModeWidgets = new ArrayList<>();

    // Cached layout coordinates for the bottom-left Move Panel
    private int movePanelX;
    private int movePanelY;
    private int movePanelW;
    private int movePanelH;
    private int moveRowX;
    private int moveStartY;

    // Guard flag prevents EditBox responders from re-triggering during programmatic sync
    private boolean updatingBoxes = false;

    public GuiSchematicControl() {
        super(Component.literal("Schematic Control"));
    }

    @Override
    protected void init() {
        this.fullModeWidgets.clear();

        int centerX = this.width / 2;
        int rowSpacing = 24;
        int margin = 12;

        // --- BOTTOM-LEFT PANEL: Coordinates, Move Here & Rotate (Visible in BOTH Full & Compact Move Modes) ---
        this.movePanelW = 148;
        this.movePanelH = (rowSpacing * 4) + 12; // 3 axis rows + Move/Rotate row + padding
        this.movePanelX = margin;
        this.movePanelY = this.height - this.movePanelH - margin;
        this.moveRowX = this.movePanelX + 8;
        this.moveStartY = this.movePanelY + 8;

        this.xBox = createAxisRow("X:", this.moveRowX, this.moveStartY, 0);
        this.yBox = createAxisRow("Y:", this.moveRowX, this.moveStartY + rowSpacing, 1);
        this.zBox = createAxisRow("Z:", this.moveRowX, this.moveStartY + (rowSpacing * 2), 2);

        // Row 4: Move Here (62px) + Rotate (49px) = 114px total
        this.addRenderableWidget(Button.builder(Component.literal("Move Here"), b -> {
            if (this.minecraft != null && this.minecraft.player != null) {
                var active = SchematicPlacement.getActiveSchematic();
                if (active != null) {
                    SchematicPlacement.setOrigin(
                            SchematicPlacement.calculateFrontOrigin(
                                    this.minecraft.player,
                                    active.getSchematic().getSize(),
                                    2
                            )
                    );
                } else {
                    SchematicPlacement.setOrigin(this.minecraft.player.blockPosition());
                }
                syncBoxesFromPlacement();
                setCompactMoveMode(true);
            }
        }).bounds(this.moveRowX + 18, this.moveStartY + (rowSpacing * 3), 62, 20).build());

        this.addRenderableWidget(Button.builder(
                Component.literal(getRotateButtonText()),
                b -> {
                    if (hasShiftDown()) {
                        SchematicPlacement.rotateCounterClockwise();
                    } else {
                        SchematicPlacement.rotateClockwise();
                    }
                    b.setMessage(Component.literal(getRotateButtonText()));
                }
        ).bounds(this.moveRowX + 83, this.moveStartY + (rowSpacing * 3), 49, 20).build());

        // Compact-only button to restore the full menu
        this.expandBtn = this.addRenderableWidget(Button.builder(Component.literal("Expand Menu"), b -> {
            setCompactMoveMode(false);
        }).bounds(this.movePanelX, this.movePanelY - 24, this.movePanelW, 20).build());


        // --- RIGHT EDGE: Utilities & Slicing (Hidden in Compact Mode) ---
        int utilW = 85;
        int utilX = this.width - utilW - margin;
        int utilStartY = Math.max(36, (this.height - (rowSpacing * 8)) / 2);

        // Row 1: Unload (Half Width) & Hide/Show (Half Width)
        addFullModeWidget(Button.builder(Component.literal("Unload"), b -> {
            SchematicPlacement.setActiveSchematic(null);
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
        }).bounds(utilX, utilStartY, 40, 20).build());

        addFullModeWidget(Button.builder(Component.literal(SchematicPlacement.isHidden() ? "Show" : "Hide"), b -> {
            SchematicPlacement.setHidden(!SchematicPlacement.isHidden());
            b.setMessage(Component.literal(SchematicPlacement.isHidden() ? "Show" : "Hide"));
        }).bounds(utilX + 45, utilStartY, 40, 20).build());

        // Row 2: Layer Mode Toggle
        this.layerModeBtn = addFullModeWidget(Button.builder(Component.literal("Layers: ALL"), b -> {
            cycleLayerMode();
        }).bounds(utilX, utilStartY + rowSpacing, utilW, 20).build());

        // Row 3: Layer Selection (-, Box, +)
        this.layerMinusBtn = addFullModeWidget(Button.builder(Component.literal("-"), b -> {
            nudgeLayer(hasShiftDown() ? -5 : -1);
        }).bounds(utilX, utilStartY + (rowSpacing * 2), 20, 20).build());

        this.layerBox = new EditBox(this.font, utilX + 22, utilStartY + (rowSpacing * 2) + 1, 41, 18, Component.literal("Layer"));
        this.layerBox.setMaxLength(4);
        this.layerBox.setFilter(text -> text.matches("-?\\d*"));
        this.layerBox.setResponder(text -> {
            if (this.updatingBoxes || text.isEmpty() || text.equals("-")) return;
            try {
                setLayerSafe(Integer.parseInt(text));
            } catch (NumberFormatException ignored) {}
        });
        addFullModeWidget(this.layerBox);

        this.layerPlusBtn = addFullModeWidget(Button.builder(Component.literal("+"), b -> {
            nudgeLayer(hasShiftDown() ? 5 : 1);
        }).bounds(utilX + 65, utilStartY + (rowSpacing * 2), 20, 20).build());

        // Row 4: Materials
        addFullModeWidget(Button.builder(Component.literal("Materials"), b -> {
            if (SchematicPlacement.getActiveSchematic() != null && this.minecraft != null) {
                this.minecraft.setScreen(new GuiSchematicMaterials(this));
            }
        }).bounds(utilX, utilStartY + (rowSpacing * 3), utilW, 20).build());

        // Row 5: Printer Toggle
        addFullModeWidget(Button.builder(
                Component.literal(getPrinterButtonText()),
                b -> {
                    SchematicPlacement.togglePrinter();
                    b.setMessage(Component.literal(getPrinterButtonText()));
                }
        ).bounds(utilX, utilStartY + (rowSpacing * 4), utilW, 20).build());

        // Row 6: Excavation Toggle
        addFullModeWidget(Button.builder(
                Component.literal(getExcavationButtonText()),
                b -> {
                    SchematicPlacement.setExcavateAir(!SchematicPlacement.isExcavateAir());
                    b.setMessage(Component.literal(getExcavationButtonText()));
                }
        ).bounds(utilX, utilStartY + (rowSpacing * 5), utilW, 20).build());

        // Row 7: Paste (enforced by server config AllowPaste)
        boolean canPaste = this.minecraft != null
                && this.minecraft.player != null
                && SchematicPlacement.getActiveSchematic() != null;

        Button pasteBtn = Button.builder(Component.literal("Paste"), b -> executeCreativePaste())
                .bounds(utilX, utilStartY + (rowSpacing * 6), utilW, 20)
                .build();
        pasteBtn.active = canPaste;
        addFullModeWidget(pasteBtn);

        // Row 8: Hide/Show Validation Glow Highlights
        addFullModeWidget(Button.builder(
                Component.literal(getValidationHighlightsButtonText()),
                b -> {
                    SchematicPlacement.toggleValidationHighlightsHidden();
                    b.setMessage(Component.literal(getValidationHighlightsButtonText()));
                }
        ).bounds(utilX, utilStartY + (rowSpacing * 7), utilW, 20).build());

        // --- BOTTOM CENTER: Done (Hidden in Compact Mode; Esc still closes) ---
        addFullModeWidget(Button.builder(Component.literal("Done"), b -> {
            if (this.minecraft != null) {
                this.minecraft.setScreen(null);
            }
        }).bounds(centerX - 50, this.height - 30, 100, 20).build());

        syncBoxesFromPlacement();
        updateLayerControls();
        setCompactMoveMode(this.compactMoveMode);
    }

    private <T extends AbstractWidget> T addFullModeWidget(T widget) {
        this.fullModeWidgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private void setCompactMoveMode(boolean compact) {
        this.compactMoveMode = compact;
        for (AbstractWidget widget : this.fullModeWidgets) {
            widget.visible = !compact;
        }
        if (this.expandBtn != null) {
            this.expandBtn.visible = compact;
        }
    }

    private String getRotateButtonText() {
        return "Rot: " + SchematicPlacement.getRotationLabel(SchematicPlacement.getRotation());
    }

    private String getExcavationButtonText() {
        return SchematicPlacement.isExcavateAir() ? "Excavate: ON" : "Excavate: OFF";
    }

    private String getPrinterButtonText() {
        return SchematicPlacement.isPrinterEnabled() ? "Printer: ON" : "Printer: OFF";
    }

    private String getValidationHighlightsButtonText() {
        return SchematicPlacement.isValidationHighlightsHidden() ? "Glow: OFF" : "Glow: ON";
    }

    private void executeCreativePaste() {
        if (this.minecraft == null || this.minecraft.player == null) return;
        var active = SchematicPlacement.getActiveSchematic();
        if (active == null) return;

        var data = active.getSchematic();
        BlockPos origin = SchematicPlacement.getOrigin();
        var rotation = SchematicPlacement.getRotation();
        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        int layerY = SchematicPlacement.getLayerY();
        boolean excavateAir = SchematicPlacement.isExcavateAir();

        MinecraftServer server = this.minecraft.getSingleplayerServer();
        if (server != null) {
            var dim = this.minecraft.player.level().dimension();
            var playerUuid = this.minecraft.player.getUUID();

            server.execute(() -> {
                ServerLevel serverLevel = server.getLevel(dim);
                ServerPlayer serverPlayer = server.getPlayerList().getPlayer(playerUuid);
                if (serverLevel == null || serverPlayer == null) return;

                SchematicPaster.PasteResult result = SchematicPaster.pasteVisibleLayers(
                        serverLevel, serverPlayer, data, data.getBlockEntityTags(), origin, rotation, mode, layerY, excavateAir
                );
                serverPlayer.displayClientMessage(Component.literal(result.message()), true);
            });

            this.minecraft.setScreen(null);
            return;
        }

        Connection connection = (this.minecraft.getConnection() != null)
                ? this.minecraft.getConnection().getConnection()
                : null;
        if (!SchematonNetwork.isRemoteChannelPresent(connection)) {
            this.minecraft.player.displayClientMessage(
                    Component.literal("Paste on dedicated servers requires the server side mod to be present"),
                    true
            );
            return;
        }

        Map<BlockPos, PasteSchematicPacket.PlacedBlockEntry> entries = collectSchematicEntries(active);
        String schName = data.getName();
        ExportProgressOverlay.start(schName, "Uploading schematic to server...");

        if (entries.size() <= PasteSchematicChunkPacket.ENTRIES_PER_CHUNK) {
            PasteSchematicPacket packet = new PasteSchematicPacket(
                    data.getSize(),
                    origin,
                    rotation,
                    mode,
                    layerY,
                    excavateAir,
                    entries
            );
            if (!SchematonNetwork.sendToServer(packet)) {
                ExportProgressOverlay.complete(false, "Failed sending paste packet.");
                this.minecraft.player.displayClientMessage(
                        Component.literal("Failed sending schematic paste packet to server"),
                        true
                );
                return;
            }
            ExportProgressOverlay.updateProgress(0.65f, "Server pasting schematic...");
        } else {
            List<List<PasteSchematicPacket.PlacedBlockEntry>> batches = partitionEntriesBySize(new ArrayList<>(entries.values()));
            int totalChunks = batches.size();
            UUID transferId = UUID.randomUUID();

            for (int i = 0; i < totalChunks; i++) {
                List<PasteSchematicPacket.PlacedBlockEntry> slice = batches.get(i);

                PasteSchematicChunkPacket chunkPacket = new PasteSchematicChunkPacket(
                        transferId,
                        i,
                        totalChunks,
                        data.getSize(),
                        origin,
                        rotation,
                        mode,
                        layerY,
                        excavateAir,
                        slice
                );
                if (!SchematonNetwork.sendToServer(chunkPacket)) {
                    ExportProgressOverlay.complete(false, "Failed uploading chunk " + (i + 1) + "/" + totalChunks);
                    this.minecraft.player.displayClientMessage(
                            Component.literal("Failed uploading schematic paste chunk to server"),
                            true
                    );
                    return;
                }
                float frac = (float) (i + 1) / (float) totalChunks;
                ExportProgressOverlay.updateProgress(frac * 0.65f, "Uploaded chunk " + (i + 1) + "/" + totalChunks + "...");
            }
            ExportProgressOverlay.updateProgress(0.70f, "Server pasting in layers...");
        }
        this.minecraft.setScreen(null);
    }

    private static Map<BlockPos, PasteSchematicPacket.PlacedBlockEntry> collectSchematicEntries(VirtualSchematicLevel active) {
        Vec3i size = active.getSchematic().getSize();
        Map<BlockPos, CompoundTag> beTags = active.getSchematic().getBlockEntityTags();
        Map<BlockPos, PasteSchematicPacket.PlacedBlockEntry> result = new HashMap<>();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    cursor.set(x, y, z);
                    BlockState state = active.getBlockState(cursor);
                    if (state.isAir()) continue;

                    BlockPos immutablePos = cursor.immutable();
                    CompoundTag beTag = null;
                    if (beTags != null && beTags.containsKey(immutablePos)) {
                        CompoundTag raw = beTags.get(immutablePos);
                        if (raw != null) {
                            beTag = raw.copy();
                        }
                    } else {
                        BlockEntity virtualBe = active.getBlockEntity(cursor);
                        if (virtualBe != null) {
                            beTag = virtualBe.saveWithFullMetadata();
                        }
                    }

                    result.put(immutablePos, new PasteSchematicPacket.PlacedBlockEntry(immutablePos, state, beTag));
                }
            }
        }
        return result;
    }

    private static List<List<PasteSchematicPacket.PlacedBlockEntry>> partitionEntriesBySize(
            List<PasteSchematicPacket.PlacedBlockEntry> allEntries
    ) {
        List<List<PasteSchematicPacket.PlacedBlockEntry>> batches = new ArrayList<>();
        List<PasteSchematicPacket.PlacedBlockEntry> current = new ArrayList<>();
        int currentBytes = 0;

        for (PasteSchematicPacket.PlacedBlockEntry entry : allEntries) {
            int entryBytes = estimateEntryBytes(entry);
            if (!current.isEmpty() && (current.size() >= PasteSchematicChunkPacket.ENTRIES_PER_CHUNK
                    || currentBytes + entryBytes > PasteSchematicChunkPacket.MAX_CHUNK_BYTES)) {
                batches.add(current);
                current = new ArrayList<>();
                currentBytes = 0;
            }
            current.add(entry);
            currentBytes += entryBytes;
        }

        if (!current.isEmpty()) {
            batches.add(current);
        }
        return batches;
    }

    private static int estimateEntryBytes(PasteSchematicPacket.PlacedBlockEntry entry) {
        int bytes = 12; // BlockPos + VarInt block state ID + boolean flag
        CompoundTag tag = entry.blockEntityTag();
        if (tag != null && !tag.isEmpty()) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DataOutputStream dos = new DataOutputStream(baos);
                NbtIo.write(tag, dos);
                bytes += baos.size();
            } catch (IOException ignored) {
                bytes += 512;
            }
        }
        return bytes;
    }

    private EditBox createAxisRow(String label, int x, int y, int axisIndex) {
        int btnWidth = 20;
        int boxWidth = 66;
        int controlStart = x + 18;

        this.addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            setCompactMoveMode(true);
            nudgeAxis(axisIndex, hasShiftDown() ? -10 : -1);
        }).bounds(controlStart, y, btnWidth, 20).build());

        EditBox box = new EditBox(this.font, controlStart + btnWidth + 4, y + 1, boxWidth, 18, Component.literal(label));
        box.setMaxLength(10);
        box.setFilter(text -> text.matches("-?\\d*"));
        box.setResponder(text -> {
            if (this.updatingBoxes) return;
            setCompactMoveMode(true);
            applyTypedCoordinate(axisIndex, text);
        });
        this.addRenderableWidget(box);

        this.addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            setCompactMoveMode(true);
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
            case ALL_BELOW -> SchematicPlacement.LayerMode.ALL_ABOVE;
            case ALL_ABOVE -> SchematicPlacement.LayerMode.SINGLE_LAYER;
            case SINGLE_LAYER -> SchematicPlacement.LayerMode.ALL;
        };
        SchematicPlacement.setLayerMode(next);
        updateLayerControls();
    }

    private void updateLayerControls() {
        SchematicPlacement.LayerMode mode = SchematicPlacement.getLayerMode();
        boolean slicingActive = (mode != SchematicPlacement.LayerMode.ALL);

        this.layerMinusBtn.active = slicingActive;
        this.layerPlusBtn.active = slicingActive;
        this.layerBox.setEditable(slicingActive);

        String modeLabel = switch (mode) {
            case ALL -> "Layers: ALL";
            case ALL_BELOW -> "Layers: BELOW";
            case ALL_ABOVE -> "Layers: ABOVE";
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
        int centerX = this.width / 2;
        int rowSpacing = 24;

        if (!this.compactMoveMode) {
            this.renderBackground(graphics);

            graphics.drawCenteredString(this.font, this.title, centerX, 12, 0xFFFFFF);

            var active = SchematicPlacement.getActiveSchematic();
            if (active != null) {
                String info = active.getSchematic().getName()
                        + " (" + active.getSchematic().getSize().toShortString() + ")"
                        + " [" + SchematicPlacement.getRotationLabel(SchematicPlacement.getRotation()) + "]";
                graphics.drawCenteredString(this.font, info, centerX, 25, 0x55FFFF);
            } else {
                graphics.drawCenteredString(this.font, "No schematic loaded", centerX, 25, 0xFF5555);
            }
        } else {
            graphics.fill(
                    this.movePanelX - 2,
                    this.movePanelY - 2,
                    this.movePanelX + this.movePanelW + 2,
                    this.movePanelY + this.movePanelH + 2,
                    0x4D000000
            );
        }

        graphics.drawString(this.font, "X:", this.moveRowX, this.moveStartY + 6, 0xFF5555);
        graphics.drawString(this.font, "Y:", this.moveRowX, this.moveStartY + rowSpacing + 6, 0x55FF55);
        graphics.drawString(this.font, "Z:", this.moveRowX, this.moveStartY + (rowSpacing * 2) + 6, 0x5555FF);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}