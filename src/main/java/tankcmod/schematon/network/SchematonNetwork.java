package tankcmod.schematon.network;

import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import tankcmod.schematon.Schematon;

import javax.annotation.Nullable;

public final class SchematonNetwork {
    private static final String PROTOCOL_VERSION = "1";
    private static int packetId = 0;

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Schematon.MODID, "main"),
            () -> PROTOCOL_VERSION,
            remoteVersion -> true,
            remoteVersion -> true
    );

    private static boolean registered = false;

    private SchematonNetwork() {
    }

    public static void register() {
        if (registered) {
            return;
        }
        registered = true;

        CHANNEL.messageBuilder(ExportSchematicRequestPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(ExportSchematicRequestPacket::encode)
                .decoder(ExportSchematicRequestPacket::decode)
                .consumerMainThread(ExportSchematicRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(ExportSchematicResponsePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(ExportSchematicResponsePacket::encode)
                .decoder(ExportSchematicResponsePacket::decode)
                .consumerMainThread(ExportSchematicResponsePacket::handle)
                .add();

        CHANNEL.messageBuilder(PasteSchematicPacket.class, nextId(), NetworkDirection.PLAY_TO_SERVER)
                .encoder(PasteSchematicPacket::encode)
                .decoder(PasteSchematicPacket::decode)
                .consumerMainThread(PasteSchematicPacket::handle)
                .add();

        CHANNEL.messageBuilder(PasteSchematicResponsePacket.class, nextId(), NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PasteSchematicResponsePacket::encode)
                .decoder(PasteSchematicResponsePacket::decode)
                .consumerMainThread(PasteSchematicResponsePacket::handle)
                .add();
    }

    private static int nextId() {
        return packetId++;
    }

    public static boolean isRemoteChannelPresent(@Nullable Connection connection) {
        return connection != null && CHANNEL.isRemotePresent(connection);
    }

    public static boolean sendToServer(Object msg) {
        if (msg == null) {
            return false;
        }
        try {
            CHANNEL.sendToServer(msg);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void sendToPlayer(Object msg, @Nullable ServerPlayer player) {
        if (msg == null || player == null) {
            return;
        }
        try {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
        } catch (Exception ignored) {
            // Avoid crashing if player channel is not active
        }
    }
}