package tankcmod.schematon.network;

import com.mojang.logging.LogUtils;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import tankcmod.schematon.compat.CompatManager;
import tankcmod.schematon.config.SchematonConfig;
import tankcmod.schematon.format.SchematicExporter;
import tankcmod.schematon.world.SchematicChunkLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public class ExportSchematicRequestPacket {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CHUNKED_EXPORT_BLOCK_THRESHOLD = 32_768;
    private static final int LAYERS_PER_TICK = 2;
    private final BlockPos pos1;
    private final BlockPos pos2;
    private final String fileName;
    private final boolean includeEntities;
    private final boolean ignoreAir;
    private final boolean ignoreContents;

    public ExportSchematicRequestPacket(
            BlockPos pos1,
            BlockPos pos2,
            String fileName,
            boolean includeEntities,
            boolean ignoreAir,
            boolean ignoreContents
    ) {
        this.pos1 = pos1;
        this.pos2 = pos2;
        this.fileName = fileName;
        this.includeEntities = includeEntities;
        this.ignoreAir = ignoreAir;
        this.ignoreContents = ignoreContents;
    }

    public static void encode(ExportSchematicRequestPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.pos1);
        buf.writeBlockPos(msg.pos2);
        buf.writeUtf(msg.fileName, 128);
        buf.writeBoolean(msg.includeEntities);
        buf.writeBoolean(msg.ignoreAir);
        buf.writeBoolean(msg.ignoreContents);
    }

    public static ExportSchematicRequestPacket decode(FriendlyByteBuf buf) {
        BlockPos p1 = buf.readBlockPos();
        BlockPos p2 = buf.readBlockPos();
        String name = buf.readUtf(128);
        boolean entities = buf.readBoolean();
        boolean noAir = buf.readBoolean();
        boolean noContents = buf.readBoolean();
        return new ExportSchematicRequestPacket(p1, p2, name, entities, noAir, noContents);
    }

    public static void handle(ExportSchematicRequestPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) {
                return;
            }

            ServerLevel level = sender.serverLevel();
            String safeName = SchematicExporter.sanitizeNbtFileName(msg.fileName);
            boolean effectiveIgnoreContents = msg.ignoreContents
                    || SchematonConfig.ALLOW_CREATE.get() == SchematonConfig.CreateMode.Empty;
            long volume = (long) (Math.abs(msg.pos2.getX() - msg.pos1.getX()) + 1)
                    * (Math.abs(msg.pos2.getY() - msg.pos1.getY()) + 1)
                    * (Math.abs(msg.pos2.getZ() - msg.pos1.getZ()) + 1);
            if (volume > CHUNKED_EXPORT_BLOCK_THRESHOLD) {
                SlicedExportJob.enqueue(sender, level, msg.pos1, msg.pos2, safeName, msg.includeEntities, msg.ignoreAir, effectiveIgnoreContents);
                return;
            }
            try {
                byte[] nbtBytes = buildCompressedSchematicBytes(
                        level,
                        msg.pos1,
                        msg.pos2,
                        msg.includeEntities,
                        msg.ignoreAir,
                        effectiveIgnoreContents
                );

                String summary = "Exported " + safeName + " [Server -> Player]"
                        + (effectiveIgnoreContents ? " (no-contents)" : "")
                        + (msg.includeEntities ? " (+entities)" : "");

                sendSchematicToPlayer(sender, safeName, summary, nbtBytes);

            } catch (Exception e) {
                LOGGER.error("Server failed exporting schematic '{}' for player {}", safeName, sender.getGameProfile().getName(), e);
                SchematonNetwork.sendToPlayer(
                        new ExportSchematicResponsePacket(false, safeName, "Server export failed: " + e.getMessage(), new byte[0]),
                        sender
                );
            }
        });
        ctx.setPacketHandled(true);
    }

    private static byte[] buildCompressedSchematicBytes(
            ServerLevel level,
            BlockPos pos1,
            BlockPos pos2,
            boolean includeEntities,
            boolean ignoreAir,
            boolean ignoreContents
    ) throws IOException {
        BlockPos minPos = new BlockPos(
                Math.min(pos1.getX(), pos2.getX()),
                Math.min(pos1.getY(), pos2.getY()),
                Math.min(pos1.getZ(), pos2.getZ())
        );
        BlockPos maxPos = new BlockPos(
                Math.max(pos1.getX(), pos2.getX()),
                Math.max(pos1.getY(), pos2.getY()),
                Math.max(pos1.getZ(), pos2.getZ())
        );

        int width = maxPos.getX() - minPos.getX() + 1;
        int height = maxPos.getY() - minPos.getY() + 1;
        int length = maxPos.getZ() - minPos.getZ() + 1;

        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndexMap = new HashMap<>();
        ListTag blocksTag = new ListTag();

        BlockState airState = Blocks.AIR.defaultBlockState();
        palette.add(airState);
        paletteIndexMap.put(airState, 0);

        BlockPos.MutableBlockPos worldCursor = new BlockPos.MutableBlockPos();

        for (int y = 0; y < height; y++) {
            int wy = minPos.getY() + y;
            for (int z = 0; z < length; z++) {
                int wz = minPos.getZ() + z;
                for (int x = 0; x < width; x++) {
                    int wx = minPos.getX() + x;
                    worldCursor.set(wx, wy, wz);

                    if (!level.isLoaded(worldCursor)) {
                        continue;
                    }

                    BlockState state = level.getBlockState(worldCursor);
                    if (state.isAir() && ignoreAir) {
                        continue;
                    }

                    int stateId = paletteIndexMap.computeIfAbsent(state, s -> {
                        int idx = palette.size();
                        palette.add(s);
                        return idx;
                    });

                    CompoundTag blockEntry = new CompoundTag();
                    ListTag posList = new ListTag();
                    posList.add(IntTag.valueOf(x));
                    posList.add(IntTag.valueOf(y));
                    posList.add(IntTag.valueOf(z));
                    blockEntry.put("pos", posList);
                    blockEntry.putInt("state", stateId);

                    BlockEntity be = level.getBlockEntity(worldCursor);
                    if (be != null) {
                        CompoundTag rawBeTag = be.saveWithFullMetadata();
                        BlockPos immutableWorldPos = worldCursor.immutable();
                        CompoundTag preparedBeTag = CompatManager.prepareSaveTag(be, rawBeTag, immutableWorldPos, level);
                        if (be instanceof RandomizableContainerBlockEntity || rawBeTag.contains("Items") || (preparedBeTag != null && preparedBeTag.contains("Items"))) {
                            LOGGER.info("[Schematon Export] Server BE at {} ({}) | ignoreContents={} | rawItems={} | preparedItems={}",
                                    immutableWorldPos,
                                    be.getType(),
                                    ignoreContents,
                                    rawBeTag.contains("Items") ? rawBeTag.get("Items") : "<none>",
                                    (preparedBeTag != null && preparedBeTag.contains("Items")) ? preparedBeTag.get("Items") : "<none>");
                        }
                        if (preparedBeTag != null && !preparedBeTag.isEmpty()) {
                            CompoundTag beCopy = preparedBeTag.copy();
                            if (ignoreContents) {
                                stripItemContents(beCopy);
                            }
                            beCopy.putInt("x", x);
                            beCopy.putInt("y", y);
                            beCopy.putInt("z", z);
                            blockEntry.put("nbt", beCopy);
                        }
                    }

                    blocksTag.add(blockEntry);
                }
            }
        }

        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }

        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(width));
        sizeTag.add(IntTag.valueOf(height));
        sizeTag.add(IntTag.valueOf(length));
        root.put("size", sizeTag);
        root.put("palette", paletteTag);
        root.put("blocks", blocksTag);

        if (includeEntities) {
            ListTag entitiesTag = new ListTag();
            AABB bounds = new AABB(
                    minPos.getX(), minPos.getY(), minPos.getZ(),
                    maxPos.getX() + 1.0, maxPos.getY() + 1.0, maxPos.getZ() + 1.0
            );
            List<Entity> entities = level.getEntities((Entity) null, bounds, entity -> !(entity instanceof Player));
            for (Entity entity : entities) {
                CompoundTag nbt = new CompoundTag();
                if (!entity.saveAsPassenger(nbt)) {
                    continue;
                }
                nbt.remove("UUID");

                Vec3 relPos = entity.position().subtract(minPos.getX(), minPos.getY(), minPos.getZ());
                ListTag localPosTag = new ListTag();
                localPosTag.add(DoubleTag.valueOf(relPos.x));
                localPosTag.add(DoubleTag.valueOf(relPos.y));
                localPosTag.add(DoubleTag.valueOf(relPos.z));

                CompoundTag entityEntry = new CompoundTag();
                entityEntry.put("pos", localPosTag);
                entityEntry.put("nbt", nbt);
                entitiesTag.add(entityEntry);
            }
            root.put("entities", entitiesTag);
        }

        LOGGER.info("[Schematon Export] Compressing NBT for {} blocks...", blocksTag.size());
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, baos);
        return baos.toByteArray();
    }

    private static void stripItemContents(CompoundTag tag) {
        tag.remove("Items");
        tag.remove("Inventory");
        tag.remove("ItemStack");
        tag.remove("Item");

        Iterator<String> keyIterator = tag.getAllKeys().iterator();
        while (keyIterator.hasNext()) {
            String key = keyIterator.next();
            Tag entry = tag.get(key);
            if (entry instanceof CompoundTag subCompound) {
                if (looksLikeItemStack(subCompound)) {
                    keyIterator.remove();
                } else {
                    stripItemContents(subCompound);
                }
            } else if (entry instanceof ListTag listTag) {
                stripItemList(listTag);
            }
        }
    }

    private static void stripItemList(ListTag listTag) {
        for (int i = listTag.size() - 1; i >= 0; i--) {
            Tag element = listTag.get(i);
            if (element instanceof CompoundTag compound) {
                if (looksLikeItemStack(compound)) {
                    listTag.remove(i);
                } else {
                    stripItemContents(compound);
                }
            } else if (element instanceof ListTag nestedList) {
                stripItemList(nestedList);
            }
        }
    }

    private static boolean looksLikeItemStack(CompoundTag compound) {
        return compound.contains("id", Tag.TAG_STRING)
                && (compound.contains("Count", Tag.TAG_ANY_NUMERIC) || compound.contains("count", Tag.TAG_ANY_NUMERIC));
    }

    private static void sendSchematicToPlayer(ServerPlayer player, String safeName, String summary, byte[] nbtBytes) {
        if (nbtBytes.length <= ExportSchematicChunkPacket.CHUNK_SIZE_BYTES) {
            SchematonNetwork.sendToPlayer(
                    new ExportSchematicResponsePacket(true, safeName, summary, nbtBytes),
                    player
            );
            return;
        }

        int totalChunks = (nbtBytes.length + ExportSchematicChunkPacket.CHUNK_SIZE_BYTES - 1)
                / ExportSchematicChunkPacket.CHUNK_SIZE_BYTES;
        LOGGER.info("[Schematon Export] Sending '{}' ({} bytes) in {} chunks", safeName, nbtBytes.length, totalChunks);

        for (int i = 0; i < totalChunks; i++) {
            int start = i * ExportSchematicChunkPacket.CHUNK_SIZE_BYTES;
            int end = Math.min(nbtBytes.length, start + ExportSchematicChunkPacket.CHUNK_SIZE_BYTES);
            byte[] slice = new byte[end - start];
            System.arraycopy(nbtBytes, start, slice, 0, slice.length);

            SchematonNetwork.sendToPlayer(
                    new ExportSchematicChunkPacket(safeName, summary, i, totalChunks, nbtBytes.length, slice),
                    player
            );
        }
    }

    public static final class SlicedExportJob {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final BlockPos minPos;
        private final BlockPos maxPos;
        private final String safeName;
        private final boolean includeEntities;
        private final boolean ignoreAir;
        private final boolean ignoreContents;

        private final int width;
        private final int height;
        private final int length;
        private final List<BlockState> palette = new ArrayList<>();
        private final Map<BlockState, Integer> paletteIndexMap = new HashMap<>();
        private final ListTag blocksTag = new ListTag();
        private final UUID exportJobId = UUID.randomUUID();
        private final BlockPos.MutableBlockPos worldCursor = new BlockPos.MutableBlockPos();
        private int currentY = 0;

        private SlicedExportJob(
                ServerPlayer player,
                ServerLevel level,
                BlockPos pos1,
                BlockPos pos2,
                String safeName,
                boolean includeEntities,
                boolean ignoreAir,
                boolean ignoreContents
        ) {
            this.player = player;
            this.level = level;
            this.minPos = new BlockPos(
                    Math.min(pos1.getX(), pos2.getX()),
                    Math.min(pos1.getY(), pos2.getY()),
                    Math.min(pos1.getZ(), pos2.getZ())
            );
            this.maxPos = new BlockPos(
                    Math.max(pos1.getX(), pos2.getX()),
                    Math.max(pos1.getY(), pos2.getY()),
                    Math.max(pos1.getZ(), pos2.getZ())
            );
            this.safeName = safeName;
            this.includeEntities = includeEntities;
            this.ignoreAir = ignoreAir;
            this.ignoreContents = ignoreContents;
            this.width = maxPos.getX() - minPos.getX() + 1;
            this.height = maxPos.getY() - minPos.getY() + 1;
            this.length = maxPos.getZ() - minPos.getZ() + 1;

            BlockState airState = Blocks.AIR.defaultBlockState();
            palette.add(airState);
            paletteIndexMap.put(airState, 0);
        }

        static void enqueue(
                ServerPlayer player,
                ServerLevel level,
                BlockPos pos1,
                BlockPos pos2,
                String safeName,
                boolean includeEntities,
                boolean ignoreAir,
                boolean ignoreContents
        ) {
            MinecraftForge.EVENT_BUS.register(new SlicedExportJob(
                    player, level, pos1, pos2, safeName, includeEntities, ignoreAir, ignoreContents
            ));
        }

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (!player.connection.connection.isConnected() || player.level() != level) {
                SchematicChunkLoader.releaseAll(exportJobId);
                MinecraftForge.EVENT_BUS.unregister(this);
                return;
            }

            int layersProcessedThisTick = 0;
            while (currentY < height && layersProcessedThisTick < LAYERS_PER_TICK) {
                int wy = minPos.getY() + currentY;
                for (int z = 0; z < length; z++) {
                    int wz = minPos.getZ() + z;
                    for (int x = 0; x < width; x++) {
                        int wx = minPos.getX() + x;
                        worldCursor.set(wx, wy, wz);
                        if (wy == minPos.getY()) {
                            SchematicChunkLoader.ensureChunkLoaded(level, exportJobId, worldCursor);
                        }
                        if (!level.isLoaded(worldCursor)) continue;

                        BlockState state = level.getBlockState(worldCursor);
                        if (state.isAir() && ignoreAir) continue;

                        int stateId = paletteIndexMap.computeIfAbsent(state, s -> {
                            int idx = palette.size();
                            palette.add(s);
                            return idx;
                        });

                        CompoundTag blockEntry = new CompoundTag();
                        ListTag posList = new ListTag();
                        posList.add(IntTag.valueOf(x));
                        posList.add(IntTag.valueOf(currentY));
                        posList.add(IntTag.valueOf(z));
                        blockEntry.put("pos", posList);
                        blockEntry.putInt("state", stateId);

                        BlockEntity be = level.getBlockEntity(worldCursor);
                        if (be != null) {
                            CompoundTag rawBeTag = be.saveWithFullMetadata();
                            BlockPos immutableWorldPos = worldCursor.immutable();
                            CompoundTag preparedBeTag = CompatManager.prepareSaveTag(be, rawBeTag, immutableWorldPos, level);
                            if (be instanceof RandomizableContainerBlockEntity || rawBeTag.contains("Items") || (preparedBeTag != null && preparedBeTag.contains("Items"))) {
                                LOGGER.info("[Schematon Export] Server BE at {} ({}) | ignoreContents={} | rawItems={} | preparedItems={}",
                                        immutableWorldPos,
                                        be.getType(),
                                        ignoreContents,
                                        rawBeTag.contains("Items") ? rawBeTag.get("Items") : "<none>",
                                        (preparedBeTag != null && preparedBeTag.contains("Items")) ? preparedBeTag.get("Items") : "<none>");
                            }
                            if (preparedBeTag != null && !preparedBeTag.isEmpty()) {
                                CompoundTag beCopy = preparedBeTag.copy();
                                if (ignoreContents) {
                                    stripItemContents(beCopy);
                                }
                                beCopy.putInt("x", x);
                                beCopy.putInt("y", currentY);
                                beCopy.putInt("z", z);
                                blockEntry.put("nbt", beCopy);
                            }
                        }
                        blocksTag.add(blockEntry);
                    }
                }
                currentY++;
                layersProcessedThisTick++;
            }

            if (currentY < height) {
                return;
            }

            MinecraftForge.EVENT_BUS.unregister(this);

            try {
                ListTag paletteTag = new ListTag();
                for (BlockState state : palette) {
                    paletteTag.add(NbtUtils.writeBlockState(state));
                }

                CompoundTag root = new CompoundTag();
                root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());

                ListTag sizeTag = new ListTag();
                sizeTag.add(IntTag.valueOf(width));
                sizeTag.add(IntTag.valueOf(height));
                sizeTag.add(IntTag.valueOf(length));
                root.put("size", sizeTag);
                root.put("palette", paletteTag);
                root.put("blocks", blocksTag);

                if (includeEntities) {
                    ListTag entitiesTag = new ListTag();
                    AABB bounds = new AABB(
                            minPos.getX(), minPos.getY(), minPos.getZ(),
                            maxPos.getX() + 1.0, maxPos.getY() + 1.0, maxPos.getZ() + 1.0
                    );
                    List<Entity> entities = level.getEntities((Entity) null, bounds, entity -> !(entity instanceof Player));
                    for (Entity entity : entities) {
                        CompoundTag nbt = new CompoundTag();
                        if (!entity.saveAsPassenger(nbt)) continue;
                        nbt.remove("UUID");

                        Vec3 relPos = entity.position().subtract(minPos.getX(), minPos.getY(), minPos.getZ());
                        ListTag localPosTag = new ListTag();
                        localPosTag.add(DoubleTag.valueOf(relPos.x));
                        localPosTag.add(DoubleTag.valueOf(relPos.y));
                        localPosTag.add(DoubleTag.valueOf(relPos.z));

                        CompoundTag entityEntry = new CompoundTag();
                        entityEntry.put("pos", localPosTag);
                        entityEntry.put("nbt", nbt);
                        entitiesTag.add(entityEntry);
                    }
                    root.put("entities", entitiesTag);
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                NbtIo.writeCompressed(root, baos);
                byte[] nbtBytes = baos.toByteArray();
                LOGGER.info("[Schematon Export] Sliced export '{}' compressed to {} bytes", safeName, nbtBytes.length);

                String summary = "Exported " + safeName + " [Server -> Player]"
                        + (ignoreContents ? " (no-contents)" : "")
                        + (includeEntities ? " (+entities)" : "");

                sendSchematicToPlayer(player, safeName, summary, nbtBytes);
            } catch (Exception e) {
                LOGGER.error("Server failed sliced export of schematic '{}' for player {}", safeName, player.getGameProfile().getName(), e);
                SchematonNetwork.sendToPlayer(
                        new ExportSchematicResponsePacket(false, safeName, "Server export failed: " + e.getMessage(), new byte[0]),
                        player
                );
            } finally {
                SchematicChunkLoader.releaseAll(exportJobId);
            }
        }
    }
}