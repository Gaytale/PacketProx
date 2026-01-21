package net.nova.gaytale.packetprox.mixins;

import com.google.gson.*;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.CachedPacket;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.PacketRegistry;
import com.hypixel.hytale.server.core.io.PacketHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.handler.codec.quic.QuicConnectionAddress;
import io.netty.handler.codec.quic.QuicStreamAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nonnull;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

@Mixin(PacketHandler.class)
public abstract class PacketHandlerMixin {
    @Unique
    private static final Map<String, FileOutputStream> gaytale$WRITERS = new HashMap<>();
    @Unique
    private static final Gson gaytale$GSON_BASE = new GsonBuilder()
            .setFormattingStyle(FormattingStyle.COMPACT)
            .serializeSpecialFloatingPointValues()
            .registerTypeAdapter(Class.class, (JsonSerializer<Class<?>>) (c, _, _) -> new JsonPrimitive(c.getCanonicalName()))
            .registerTypeAdapter(ByteBuffer.class, (JsonSerializer<ByteBuffer>) (b, _, _) -> {
                JsonArray array = new JsonArray();
                for (byte by : b.array()) {
                    array.add(by);
                }
                return array;
            })
            .registerTypeAdapter(ByteOrder.class, (JsonSerializer<ByteOrder>) (b, _, _) -> new JsonPrimitive(b.toString()))
            .create();
    @Unique
    private static final Gson gaytale$GSON = gaytale$GSON_BASE.newBuilder()
            .registerTypeAdapter(CachedPacket.class, (JsonSerializer<CachedPacket<?>>) (c, _, _) -> {
                Class<?> packetClass = c.getPacketType();
                try {
                    ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(c.computeSize());
                    c.serialize(buf);

                    Packet packet = (Packet) packetClass.getMethod("deserialize", ByteBuf.class, int.class).invoke(null, buf, 0);

                    return gaytale$GSON_BASE.toJsonTree(packet);
                } catch (IllegalAccessException | InvocationTargetException |
                         NoSuchMethodException e) {
                    return JsonNull.INSTANCE;
                }
            })
            .create();
    @Unique
    private static final Path gaytale$logPath = Path.of("packetprox");

    @Shadow
    @Nonnull
    public abstract Channel getChannel();

    @Inject(
            method = "handle(Lcom/hypixel/hytale/protocol/Packet;)V",
            at = @At("HEAD")
    )
    private void injectHandle(Packet packet, CallbackInfo ci) {
        gaytale$logReceive(getChannel().remoteAddress(), packet);
    }

    @Inject(
            method = "write([Lcom/hypixel/hytale/protocol/Packet;)V",
            at = @At("HEAD")
    )
    private void injectWrite0(Packet[] packets, CallbackInfo ci) {
        for (Packet packet : packets) {
            gaytale$logSend(getChannel().remoteAddress(), packet);
        }
    }

    @Inject(
            method = "write([Lcom/hypixel/hytale/protocol/Packet;Lcom/hypixel/hytale/protocol/Packet;)V",
            at = @At("HEAD")
    )
    private void injectWrite1(Packet[] packets, Packet finalPacket, CallbackInfo ci) {
        for (Packet packet : packets) {
            gaytale$logSend(getChannel().remoteAddress(), packet);
        }
        gaytale$logSend(getChannel().remoteAddress(), finalPacket);
    }

    @Inject(
            method = "disconnect(Ljava/lang/String;)V",
            at = @At("HEAD")
    )
    private void injectDisconnect(String message, CallbackInfo ci) {
        gaytale$clean(getChannel().remoteAddress());
    }

    @Unique
    private static void gaytale$logReceive(SocketAddress address, Packet packet) {
        try {
            gaytale$log(gaytale$getSocketAddressFileName(address), packet, "C2S");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static void gaytale$logSend(SocketAddress address, Packet packet) {
        try {
            gaytale$log(gaytale$getSocketAddressFileName(address), packet, "S2C");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static void gaytale$clean(SocketAddress address) {
        FileOutputStream stream = gaytale$WRITERS.remove(gaytale$getSocketAddressFileName(address));
        try {
            stream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Unique
    private static void gaytale$log(String fileName, Packet packet, String type) throws IOException {
        Path ipPath = gaytale$logPath.resolve(fileName + ".txt");

        if (!gaytale$WRITERS.containsKey(fileName)) {
            Files.deleteIfExists(ipPath);
            Files.createDirectories(ipPath.getParent());
            Files.createFile(ipPath);
            FileOutputStream stream = new FileOutputStream(ipPath.toFile(), true);
            gaytale$WRITERS.put(fileName, stream);
        }

        FileOutputStream stream = gaytale$WRITERS.get(fileName);
        stream.write("[%s] ".formatted(type).getBytes(StandardCharsets.UTF_8));
        gaytale$writePacket(stream, packet);
    }

    @Unique
    private static void gaytale$writePacket(FileOutputStream stream, Packet packet) throws IOException {
        String packetName = PacketRegistry.getById(packet.getId()).name();
        stream.write("%s (%d): ".formatted(packetName, packet.getId()).getBytes(StandardCharsets.UTF_8));
        try {
            stream.write(gaytale$GSON.toJson(packet).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            HytaleLogger.getLogger().at(Level.SEVERE).log(packet.getClass().getSimpleName(), t);
            stream.write("Unable to decode.".getBytes(StandardCharsets.UTF_8));
        }
        stream.write("\n".getBytes(StandardCharsets.UTF_8));
    }

    @Unique
    private static String gaytale$getSocketAddressFileName(SocketAddress address) {
        if (address instanceof QuicConnectionAddress quicConnection) {
            String connectionId = quicConnection.toString();
            if (connectionId.startsWith("QuicConnectionAddress{connId=")) {
                connectionId = "QuicConnectionAddress-" + connectionId.substring("QuicConnectionAddress{connId=".length(), connectionId.length() - 1);
            } else if (connectionId.equals("QuicConnectionAddress{EPHEMERAL}")) {
                connectionId = "QuicConnectionAddress-EPHEMERAL";
            } else {
                connectionId = "QuicConnectionAddress-UNKNOWN";
            }
            return connectionId;
        } else if (address instanceof QuicStreamAddress quicStream) {
            return "QuicStreamAddress-" + quicStream.streamId();
        } else {
            return address.toString();
        }
    }
}
