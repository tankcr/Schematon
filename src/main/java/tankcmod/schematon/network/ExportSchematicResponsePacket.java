package tankcmod.schematon.network;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.slf4j.Logger;
import tankcmod.schematon.format.SchematicExporter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.function.Supplier;

public class ExportSchematicResponsePacket {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final boolean success;
    private final String fileName;
    private final String message;
    private final byte[] nbtBytes;

    public ExportSchematicResponsePacket(boolean success, String fileName, String message, byte[] nbtBytes) {
        this.success = success;
        this.fileName = fileName;
        this.message = message;
        this.nbtBytes = nbtBytes != null ? nbtBytes : new byte[0];
    }

    public static void encode(ExportSchematicResponsePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.fileName, 128);
        buf.writeUtf(msg.message, 512);
        buf.writeByteArray(msg.nbtBytes);
    }

    public static ExportSchematicResponsePacket decode(FriendlyByteBuf buf) {
        boolean ok = buf.readBoolean();
        String name = buf.readUtf(128);
        String msgText = buf.readUtf(512);
        byte[] bytes = buf.readByteArray();
        return new ExportSchematicResponsePacket(ok, name, msgText, bytes);
    }


    public static void handle(ExportSchematicResponsePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.applyResponse(msg)));
        ctx.setPacketHandled(true);
    }

    private static final class ClientHandler {
        private static void applyResponse(ExportSchematicResponsePacket msg) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }

            if (!msg.success || msg.nbtBytes.length == 0) {
                mc.player.displayClientMessage(Component.literal("§c[Schematon] " + msg.message), false);
                return;
            }

            File schematicsDir = new File(mc.gameDirectory, "schematics");
            if (!schematicsDir.exists() && !schematicsDir.mkdirs()) {
                mc.player.displayClientMessage(
                        Component.literal("§c[Schematon] Failed creating schematics folder: " + schematicsDir.getAbsolutePath()),
                        false
                );
                return;
            }

            String safeName = SchematicExporter.sanitizeNbtFileName(msg.fileName);
            File outFile = new File(schematicsDir, safeName);

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(msg.nbtBytes);
                mc.player.displayClientMessage(Component.literal("§a[Schematon] " + msg.message), false);
            } catch (IOException e) {
                LOGGER.error("Failed saving received schematic '{}' to {}", safeName, outFile.getAbsolutePath(), e);
                mc.player.displayClientMessage(
                        Component.literal("§c[Schematon] Failed saving .nbt locally: " + e.getMessage()),
                        false
                );
            }
        }
    }
}