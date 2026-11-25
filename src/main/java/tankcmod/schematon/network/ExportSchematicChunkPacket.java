package tankcmod.schematon.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import tankcmod.schematon.client.gui.ExportProgressOverlay;
import tankcmod.schematon.format.SchematicExporter;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

public class ExportSchematicChunkPacket {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int CHUNK_SIZE_BYTES = 262_144; // 256 KB per packet (well under 1 MB limit)

    private final String fileName;
    private final String summary;
    private final int chunkIndex;
    private final int totalChunks;
    private final int totalBytes;
    private final byte[] chunkData;

    public ExportSchematicChunkPacket(
            String fileName,
            String summary,
            int chunkIndex,
            int totalChunks,
            int totalBytes,
            byte[] chunkData
    ) {
        this.fileName = fileName;
        this.summary = summary;
        this.chunkIndex = chunkIndex;
        this.totalChunks = totalChunks;
        this.totalBytes = totalBytes;
        this.chunkData = chunkData != null ? chunkData : new byte[0];
    }

    public static void encode(ExportSchematicChunkPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.fileName, 128);
        buf.writeUtf(msg.summary, 512);
        buf.writeInt(msg.chunkIndex);
        buf.writeInt(msg.totalChunks);
        buf.writeInt(msg.totalBytes);
        buf.writeByteArray(msg.chunkData);
    }

    public static ExportSchematicChunkPacket decode(FriendlyByteBuf buf) {
        String name = buf.readUtf(128);
        String sum = buf.readUtf(512);
        int idx = buf.readInt();
        int total = buf.readInt();
        int totalSz = buf.readInt();
        byte[] data = buf.readByteArray();
        return new ExportSchematicChunkPacket(name, sum, idx, total, totalSz, data);
    }

    public static void handle(ExportSchematicChunkPacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.handleChunk(msg)));
        ctx.setPacketHandled(true);
    }

    private static final class ClientHandler {
        private static final Map<String, byte[][]> IN_FLIGHT = new HashMap<>();

        private static void handleChunk(ExportSchematicChunkPacket msg) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }

            if (msg.totalChunks <= 0 || msg.chunkIndex < 0 || msg.chunkIndex >= msg.totalChunks) {
                IN_FLIGHT.remove(msg.fileName);
                String err = "Invalid schematic chunk index (" + msg.chunkIndex + "/" + msg.totalChunks + ").";
                ExportProgressOverlay.complete(false, err);
                mc.player.displayClientMessage(Component.literal("§c[Schematon] " + err), false);
                return;
            }

            byte[][] parts = IN_FLIGHT.computeIfAbsent(msg.fileName, k -> new byte[msg.totalChunks][]);
            if (parts.length != msg.totalChunks) {
                parts = new byte[msg.totalChunks][];
                IN_FLIGHT.put(msg.fileName, parts);
            }
            parts[msg.chunkIndex] = msg.chunkData;

            int receivedCount = 0;
            for (byte[] part : parts) {
                if (part != null) {
                    receivedCount++;
                }
            }

            float transferFrac = (float) receivedCount / (float) msg.totalChunks;
            ExportProgressOverlay.updateProgress(
                    0.35f + (transferFrac * 0.55f),
                    "Downloading schematic (" + receivedCount + "/" + msg.totalChunks + " chunks)..."
            );

            if (receivedCount < msg.totalChunks) {
                return;
            }

            IN_FLIGHT.remove(msg.fileName);

            try {
                ByteArrayOutputStream assembled = new ByteArrayOutputStream(Math.max(1024, msg.totalBytes));
                for (byte[] part : parts) {
                    if (part == null) {
                        throw new IOException("Missing schematic chunk during reassembly.");
                    }
                    assembled.write(part);
                }

                ExportProgressOverlay.updateProgress(0.92f, "Saving received .nbt...");

                File schematicsDir = new File(mc.gameDirectory, "schematics");
                if (!schematicsDir.exists() && !schematicsDir.mkdirs()) {
                    String err = "Failed creating schematics folder: " + schematicsDir.getAbsolutePath();
                    ExportProgressOverlay.complete(false, err);
                    mc.player.displayClientMessage(Component.literal("§c[Schematon] " + err), false);
                    return;
                }

                String safeName = SchematicExporter.sanitizeNbtFileName(msg.fileName);
                File outFile = new File(schematicsDir, safeName);

                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(assembled.toByteArray());
                }

                ExportProgressOverlay.complete(true, msg.summary);
                mc.player.displayClientMessage(Component.literal("§a[Schematon] " + msg.summary), false);
            } catch (IOException e) {
                LOGGER.error("Failed reassembling/saving chunked schematic '{}'", msg.fileName, e);
                String err = "Failed saving chunked .nbt: " + e.getMessage();
                ExportProgressOverlay.complete(false, err);
                mc.player.displayClientMessage(Component.literal("§c[Schematon] " + err), false);
            }
        }
    }
}