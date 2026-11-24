package tankcmod.schematon.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import tankcmod.schematon.compat.CompatManager;
import tankcmod.schematon.config.SchematonConfig;
import tankcmod.schematon.world.SchematicPlacement;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class PasteSchematicPacket {
    private static final Logger LOGGER = LogUtils.getLogger();

    public record PlacedBlockEntry(BlockPos localPos, BlockState state, CompoundTag blockEntityTag) {}

    private final Vec3i size;
    private final BlockPos origin;
    private final Rotation rotation;
    private final SchematicPlacement.LayerMode layerMode;
    private final int layerY;
    private final boolean excavateAir;
    private final Map<BlockPos, PlacedBlockEntry> entriesByLocalPos;

    public PasteSchematicPacket(
            Vec3i size,
            BlockPos origin,
            Rotation rotation,
            SchematicPlacement.LayerMode layerMode,
            int layerY,
            boolean excavateAir,
            Map<BlockPos, PlacedBlockEntry> entriesByLocalPos
    ) {
        this.size = size;
        this.origin = origin;
        this.rotation = rotation != null ? rotation : Rotation.NONE;
        this.layerMode = layerMode != null ? layerMode : SchematicPlacement.LayerMode.ALL;
        this.layerY = layerY;
        this.excavateAir = excavateAir;
        this.entriesByLocalPos = entriesByLocalPos != null ? entriesByLocalPos : Map.of();
    }

    public static void encode(PasteSchematicPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.size.getX());
        buf.writeVarInt(msg.size.getY());
        buf.writeVarInt(msg.size.getZ());
        buf.writeBlockPos(msg.origin);
        buf.writeEnum(msg.rotation);
        buf.writeEnum(msg.layerMode);
        buf.writeVarInt(msg.layerY);
        buf.writeBoolean(msg.excavateAir);

        buf.writeVarInt(msg.entriesByLocalPos.size());
        for (PlacedBlockEntry entry : msg.entriesByLocalPos.values()) {
            buf.writeBlockPos(entry.localPos());
            buf.writeNbt(NbtUtils.writeBlockState(entry.state()));
            boolean hasBe = entry.blockEntityTag() != null && !entry.blockEntityTag().isEmpty();
            buf.writeBoolean(hasBe);
            if (hasBe) {
                buf.writeNbt(entry.blockEntityTag());
            }
        }
    }

    public static PasteSchematicPacket decode(FriendlyByteBuf buf) {
        int sx = buf.readVarInt();
        int sy = buf.readVarInt();
        int sz = buf.readVarInt();
        Vec3i size = new Vec3i(sx, sy, sz);
        BlockPos origin = buf.readBlockPos();
        Rotation rotation = buf.readEnum(Rotation.class);
        SchematicPlacement.LayerMode mode = buf.readEnum(SchematicPlacement.LayerMode.class);
        int layerY = buf.readVarInt();
        boolean excavateAir = buf.readBoolean();

        int count = buf.readVarInt();
        Map<BlockPos, PlacedBlockEntry> map = new HashMap<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            BlockPos localPos = buf.readBlockPos().immutable();
            CompoundTag stateTag = buf.readNbt();
            BlockState state = stateTag != null
                    ? NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), stateTag)
                    : Blocks.AIR.defaultBlockState();
            boolean hasBe = buf.readBoolean();
            CompoundTag beTag = hasBe ? buf.readNbt() : null;
            map.put(localPos, new PlacedBlockEntry(localPos, state, beTag));
        }

        return new PasteSchematicPacket(size, origin, rotation, mode, layerY, excavateAir, map);
    }

    public static void handle(PasteSchematicPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }

            ServerLevel level = sender.serverLevel();
            SchematonConfig.PasteMode pasteMode = getEffectivePasteMode();

            if (pasteMode == SchematonConfig.PasteMode.Disabled) {
                SchematonNetwork.sendToPlayer(
                        new PasteSchematicResponsePacket(false, "Paste Mode is disabled by server configuration."),
                        sender
                );
                return;
            }

            boolean allowed = switch (pasteMode) {
                case All -> true;
                case OpOnly -> sender.isCreative() || sender.hasPermissions(2);
                case Disabled -> false;
            };

            if (!allowed) {
                SchematonNetwork.sendToPlayer(
                        new PasteSchematicResponsePacket(false, "Paste requires operator permissions or Creative mode."),
                        sender
                );
                return;
            }

            try {
                String resultMessage = applyPasteOnServer(level, msg);
                SchematonNetwork.sendToPlayer(
                        new PasteSchematicResponsePacket(true, resultMessage),
                        sender
                );
            } catch (Exception e) {
                LOGGER.error("Server failed pasting schematic for player {}", sender.getGameProfile().getName(), e);
                SchematonNetwork.sendToPlayer(
                        new PasteSchematicResponsePacket(false, "Server paste failed: " + e.getMessage()),
                        sender
                );
            }
        });
        ctx.setPacketHandled(true);
    }

    private static String applyPasteOnServer(ServerLevel level, PasteSchematicPacket msg) {
        Vec3i rotatedSize = SchematicPlacement.getRotatedSize(msg.size, msg.rotation);
        int setBlockFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        int placed = 0;
        int excavated = 0;
        int blockEntitiesRestored = 0;

        for (int y = 0; y < rotatedSize.getY(); y++) {
            if (msg.layerMode == SchematicPlacement.LayerMode.SINGLE_LAYER && y != msg.layerY) continue;
            if (msg.layerMode == SchematicPlacement.LayerMode.ALL_BELOW && y > msg.layerY) continue;
            if (msg.layerMode == SchematicPlacement.LayerMode.ALL_ABOVE && y < msg.layerY) continue;

            for (int rz = 0; rz < rotatedSize.getZ(); rz++) {
                for (int rx = 0; rx < rotatedSize.getX(); rx++) {
                    BlockPos sourcePos = SchematicPlacement.untransformToSourcePos(rx, y, rz, msg.size, msg.rotation);
                    BlockPos worldPos = msg.origin.offset(rx, y, rz);

                    if (!level.isLoaded(worldPos)) {
                        continue;
                    }

                    PlacedBlockEntry entry = msg.entriesByLocalPos.get(sourcePos);
                    BlockState rawState = (entry != null) ? entry.state() : Blocks.AIR.defaultBlockState();
                    BlockState schState = SchematicPlacement.applyRotation(rawState, msg.rotation);
                    BlockState currentWorldState = level.getBlockState(worldPos);

                    if (schState.isAir()) {
                        if (msg.excavateAir && !currentWorldState.isAir()) {
                            level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), setBlockFlags);
                            excavated++;
                        }
                        continue;
                    }

                    if (!currentWorldState.equals(schState)) {
                        level.setBlock(worldPos, schState, setBlockFlags);
                        placed++;
                    }

                    if (entry != null && entry.blockEntityTag() != null && !entry.blockEntityTag().isEmpty()) {
                        BlockEntity targetBe = level.getBlockEntity(worldPos);
                        if (targetBe != null) {
                            CompoundTag preparedTag = CompatManager.preparePasteTag(targetBe, entry.blockEntityTag(), worldPos, level);
                            targetBe.load(preparedTag);
                            CompatManager.onBlockEntityPasted(targetBe, preparedTag, worldPos, level);
                            blockEntitiesRestored++;
                        }
                    }
                }
            }
        }

        return "Pasted " + placed + " blocks"
                + (blockEntitiesRestored > 0 ? ", " + blockEntitiesRestored + " block entities" : "")
                + (msg.excavateAir ? " (excavated " + excavated + ")" : "") + ".";
    }

    private static SchematonConfig.PasteMode getEffectivePasteMode() {
        try {
            if (SchematonConfig.SPEC.isLoaded()) {
                return SchematonConfig.ALLOW_PASTE.get();
            }
        } catch (Exception ignored) {
        }
        return SchematonConfig.PasteMode.OpOnly;
    }
}