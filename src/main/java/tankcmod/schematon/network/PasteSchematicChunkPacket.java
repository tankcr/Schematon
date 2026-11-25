package tankcmod.schematon.network;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import tankcmod.schematon.compat.CompatManager;
import tankcmod.schematon.config.SchematonConfig;
import tankcmod.schematon.world.SchematicPlacement;
import tankcmod.schematon.world.SchematicChunkLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class PasteSchematicChunkPacket {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int ENTRIES_PER_CHUNK = 250;
    public static final int MAX_CHUNK_BYTES = 24_000;
    private static final int BLOCKS_PER_TICK = 4_000;

    private final UUID transferId;
    private final int chunkIndex;
    private final int totalChunks;
    private final Vec3i size;
    private final BlockPos origin;
    private final Rotation rotation;
    private final SchematicPlacement.LayerMode layerMode;
    private final int activeLayerY;
    private final boolean excavateAir;
    private final List<PasteSchematicPacket.PlacedBlockEntry> entries;

    public PasteSchematicChunkPacket(
            UUID transferId,
            int chunkIndex,
            int totalChunks,
            Vec3i size,
            BlockPos origin,
            Rotation rotation,
            SchematicPlacement.LayerMode layerMode,
            int activeLayerY,
            boolean excavateAir,
            List<PasteSchematicPacket.PlacedBlockEntry> entries
    ) {
        this.transferId = transferId;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.size = size;
        this.origin = origin;
        this.rotation = rotation;
        this.layerMode = layerMode;
        this.activeLayerY = activeLayerY;
        this.excavateAir = excavateAir;
        this.entries = entries != null ? entries : List.of();
    }

    public static void encode(PasteSchematicChunkPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.transferId);
        buf.writeInt(msg.chunkIndex);
        buf.writeInt(msg.totalChunks);
        buf.writeVarInt(msg.size.getX());
        buf.writeVarInt(msg.size.getY());
        buf.writeVarInt(msg.size.getZ());
        buf.writeBlockPos(msg.origin);
        buf.writeEnum(msg.rotation);
        buf.writeEnum(msg.layerMode);
        buf.writeInt(msg.activeLayerY);
        buf.writeBoolean(msg.excavateAir);

        buf.writeVarInt(msg.entries.size());
        for (PasteSchematicPacket.PlacedBlockEntry entry : msg.entries) {
            buf.writeBlockPos(entry.localPos());
            buf.writeVarInt(Block.getId(entry.state()));boolean hasBe = entry.blockEntityTag() != null && !entry.blockEntityTag().isEmpty();
            buf.writeBoolean(hasBe);
            if (hasBe) {
                buf.writeNbt(entry.blockEntityTag());
            }
        }
    }

    public static PasteSchematicChunkPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int idx = buf.readInt();
        int total = buf.readInt();
        Vec3i sz = new Vec3i(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        BlockPos orig = buf.readBlockPos();
        Rotation rot = buf.readEnum(Rotation.class);
        SchematicPlacement.LayerMode mode = buf.readEnum(SchematicPlacement.LayerMode.class);
        int layerY = buf.readInt();
        boolean excavate = buf.readBoolean();

        int count = buf.readVarInt();
        List<PasteSchematicPacket.PlacedBlockEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BlockPos pos = buf.readBlockPos().immutable();
            BlockState state = Block.stateById(buf.readVarInt());
            CompoundTag tag = buf.readBoolean() ? buf.readNbt() : null;
            list.add(new PasteSchematicPacket.PlacedBlockEntry(pos, state, tag));
        }

        return new PasteSchematicChunkPacket(id, idx, total, sz, orig, rot, mode, layerY, excavate, list);
    }

    private static final class PendingUpload {
        final int totalChunks;
        final Map<Integer, List<PasteSchematicPacket.PlacedBlockEntry>> chunks = new HashMap<>();

        PendingUpload(int totalChunks) {
            this.totalChunks = totalChunks;
        }
    }

    private static final Map<UUID, PendingUpload> IN_FLIGHT = new ConcurrentHashMap<>();

    public static void handle(PasteSchematicChunkPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;

            PendingUpload upload = IN_FLIGHT.computeIfAbsent(msg.transferId, id -> new PendingUpload(msg.totalChunks));
            upload.chunks.put(msg.chunkIndex, msg.entries);

            if (upload.chunks.size() < upload.totalChunks) {
                return;
            }

            IN_FLIGHT.remove(msg.transferId);

            SchematonConfig.PasteMode pasteMode = SchematonConfig.SPEC.isLoaded()
                    ? SchematonConfig.ALLOW_PASTE.get()
                    : SchematonConfig.PasteMode.OpOnly;

            boolean allowed = switch (pasteMode) {
                case All -> true;
                case OpOnly -> sender.isCreative() || sender.hasPermissions(2);
                case Disabled -> false;
            };

            if (!allowed) {
                SchematonNetwork.sendToPlayer(
                        new PasteSchematicResponsePacket(false, "Paste is disabled or requires OP/Creative."),
                        sender
                );
                return;
            }

            Map<BlockPos, PasteSchematicPacket.PlacedBlockEntry> merged = new HashMap<>();
            for (int i = 0; i < upload.totalChunks; i++) {
                List<PasteSchematicPacket.PlacedBlockEntry> part = upload.chunks.get(i);
                if (part != null) {
                    for (PasteSchematicPacket.PlacedBlockEntry entry : part) {
                        merged.put(entry.localPos(), entry);
                    }
                }
            }

            QueuedPasteJob.enqueue(
                    sender,
                    sender.serverLevel(),
                    msg.transferId,
                    msg.size,
                    msg.origin,
                    msg.rotation,
                    msg.layerMode,
                    msg.activeLayerY,
                    msg.excavateAir,
                    merged
            );
        });
        ctx.setPacketHandled(true);
    }

    public static final class QueuedPasteJob {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final UUID jobId;
        private final Vec3i size;
        private final Vec3i rotatedSize;
        private final BlockPos origin;
        private final Rotation rotation;
        private final SchematicPlacement.LayerMode mode;
        private final int activeLayerY;
        private final boolean excavateAir;
        private final Map<BlockPos, PasteSchematicPacket.PlacedBlockEntry> entries;

        private int curY = 0;
        private int curZ = 0;
        private int curX = 0;
        private int placed = 0;
        private int excavated = 0;
        private int blockEntitiesRestored = 0;

        private QueuedPasteJob(
                ServerPlayer player,
                ServerLevel level,
                UUID jobId,
                Vec3i size,
                BlockPos origin,
                Rotation rotation,
                SchematicPlacement.LayerMode mode,
                int activeLayerY,
                boolean excavateAir,
                Map<BlockPos, PasteSchematicPacket.PlacedBlockEntry> entries
        ) {
            this.player = player;
            this.level = level;
            this.jobId = jobId;
            this.size = size;
            this.rotation = (rotation != null) ? rotation : Rotation.NONE;
            this.rotatedSize = SchematicPlacement.getRotatedSize(size, this.rotation);
            this.origin = origin;
            this.mode = mode;
            this.activeLayerY = activeLayerY;
            this.excavateAir = excavateAir;
            this.entries = entries;
        }

        static void enqueue(
                ServerPlayer player,
                ServerLevel level,
                UUID jobId,
                Vec3i size,
                BlockPos origin,
                Rotation rotation,
                SchematicPlacement.LayerMode mode,
                int activeLayerY,
                boolean excavateAir,
                Map<BlockPos, PasteSchematicPacket.PlacedBlockEntry> entries
        ) {
            MinecraftForge.EVENT_BUS.register(new QueuedPasteJob(
                    player, level, jobId, size, origin, rotation, mode, activeLayerY, excavateAir, entries
            ));
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (!player.connection.connection.isConnected() || player.level() != level) {
                SchematicChunkLoader.releaseAll(jobId);
                MinecraftForge.EVENT_BUS.unregister(this);
                return;
            }
            int setBlockFlags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            int processedThisTick = 0;

            while (curY < rotatedSize.getY() && processedThisTick < BLOCKS_PER_TICK) {
                boolean skipLayer = (mode == SchematicPlacement.LayerMode.SINGLE_LAYER && curY != activeLayerY)
                        || (mode == SchematicPlacement.LayerMode.ALL_BELOW && curY > activeLayerY)
                        || (mode == SchematicPlacement.LayerMode.ALL_ABOVE && curY < activeLayerY);

                if (skipLayer) {
                    curY++;
                    curZ = 0;
                    curX = 0;
                    continue;
                }

                while (curZ < rotatedSize.getZ() && processedThisTick < BLOCKS_PER_TICK) {
                    while (curX < rotatedSize.getX() && processedThisTick < BLOCKS_PER_TICK) {
                        BlockPos sourcePos = SchematicPlacement.untransformToSourcePos(curX, curY, curZ, size, rotation);
                        BlockPos worldPos = origin.offset(curX, curY, curZ);
                        curX++;
                        processedThisTick++;

                        SchematicChunkLoader.ensureChunkLoaded(level, jobId, worldPos);
                        if (!level.isLoaded(worldPos)) continue;

                        PasteSchematicPacket.PlacedBlockEntry entry = entries.get(sourcePos);
                        BlockState currentWorldState = level.getBlockState(worldPos);

                        if (entry == null || entry.state().isAir()) {
                            if (excavateAir && !currentWorldState.isAir()) {
                                level.setBlock(worldPos, Blocks.AIR.defaultBlockState(), setBlockFlags);
                                excavated++;
                            }
                            continue;
                        }

                        BlockState schState = SchematicPlacement.applyRotation(entry.state(), rotation);
                        if (!currentWorldState.equals(schState)) {
                            level.setBlock(worldPos, schState, setBlockFlags);
                            placed++;
                        }

                        if (entry.blockEntityTag() != null && !entry.blockEntityTag().isEmpty()) {
                            BlockEntity targetBe = level.getBlockEntity(worldPos);
                            if (targetBe != null) {
                                CompoundTag preparedTag = CompatManager.preparePasteTag(targetBe, entry.blockEntityTag(), worldPos, level);
                                targetBe.load(preparedTag);
                                CompatManager.onBlockEntityPasted(targetBe, preparedTag, worldPos, level);
                                blockEntitiesRestored++;
                            }
                        }
                    }
                    if (curX >= rotatedSize.getX()) {
                        curX = 0;
                        curZ++;
                    }
                }
                if (curZ >= rotatedSize.getZ()) {
                    curZ = 0;
                    curY++;
                }
            }

            if (curY < rotatedSize.getY()) {
                return;
            }

            SchematicChunkLoader.releaseAll(jobId);
            MinecraftForge.EVENT_BUS.unregister(this);

            String summary = "Pasted " + placed + " blocks"
                    + (blockEntitiesRestored > 0 ? ", " + blockEntitiesRestored + " block entities" : "")
                    + (excavateAir ? " (excavated " + excavated + ")" : "") + ".";

            SchematonNetwork.sendToPlayer(new PasteSchematicResponsePacket(true, summary), player);
            player.displayClientMessage(Component.literal("§a[Schematon] " + summary), true);
        }
    }
}