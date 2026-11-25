package tankcmod.schematon.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import tankcmod.schematon.client.gui.ExportProgressOverlay;

import java.util.function.Supplier;

public class PasteSchematicResponsePacket {
    private final boolean success;
    private final String message;

    public PasteSchematicResponsePacket(boolean success, String message) {
        this.success = success;
        this.message = message != null ? message : "";
    }

    public static void encode(PasteSchematicResponsePacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.message, 512);
    }

    public static PasteSchematicResponsePacket decode(FriendlyByteBuf buf) {
        return new PasteSchematicResponsePacket(buf.readBoolean(), buf.readUtf(512));
    }

    public static void handle(PasteSchematicResponsePacket msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHandler.apply(msg)));
        ctx.setPacketHandled(true);
    }

    private static final class ClientHandler {
        private static void apply(PasteSchematicResponsePacket msg) {
            Minecraft mc = Minecraft.getInstance();
            ExportProgressOverlay.complete(msg.success, msg.message);
            if (mc.player != null) {
                mc.player.displayClientMessage(
                        Component.literal((msg.success ? "§a[Schematon] " : "§c[Schematon] ") + msg.message),
                        true
                );
            }
        }
    }
}