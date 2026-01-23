package net.nova.gaytale.packetprox;

import com.google.gson.*;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.CachedPacket;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.PacketRegistry;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.handler.codec.quic.QuicConnectionAddress;
import io.netty.handler.codec.quic.QuicStreamAddress;

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

public class PacketProx extends JavaPlugin {
    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final Map<String, FileOutputStream> WRITERS = new HashMap<>();
    private static final Gson GSON_BASE = new GsonBuilder()
            .setFormattingStyle(FormattingStyle.COMPACT)
            .serializeSpecialFloatingPointValues()
            .registerTypeAdapter(Class.class, (JsonSerializer<Class<?>>) (c, ignored0, ignored1) -> new JsonPrimitive(c.getCanonicalName()))
            .registerTypeAdapter(ByteBuffer.class, (JsonSerializer<ByteBuffer>) (b, ignored0, ignored1) -> {
                JsonArray array = new JsonArray();
                for (byte by : b.array()) {
                    array.add(by);
                }
                return array;
            })
            .registerTypeAdapter(ByteOrder.class, (JsonSerializer<ByteOrder>) (b, ignored0, ignored1) -> new JsonPrimitive(b.toString()))
            .create();
    private static final Gson GSON = GSON_BASE.newBuilder()
            .registerTypeAdapter(CachedPacket.class, (JsonSerializer<CachedPacket<?>>) (c, ignored0, ignored1) -> {
                Class<?> packetClass = c.getPacketType();
                try {
                    ByteBuf buf = PooledByteBufAllocator.DEFAULT.buffer(c.computeSize());
                    c.serialize(buf);

                    Packet packet = (Packet) packetClass.getMethod("deserialize", ByteBuf.class, int.class).invoke(null, buf, 0);

                    return GSON_BASE.toJsonTree(packet);
                } catch (IllegalAccessException | InvocationTargetException |
                         NoSuchMethodException e) {
                    return JsonNull.INSTANCE;
                }
            })
            .create();

    public PacketProx(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        PacketAdapters.registerInbound((PacketHandler handler, Packet packet) -> {
            logReceive(handler.getChannel().remoteAddress(), packet);
        });

        PacketAdapters.registerOutbound((PacketHandler handler, Packet packet) -> {
            logSend(handler.getChannel().remoteAddress(), packet);
        });
    }

    private void logReceive(SocketAddress address, Packet packet) {
        try {
            log(getSocketAddressFileName(address), packet, "C2S");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void logSend(SocketAddress address, Packet packet) {
        try {
            log(getSocketAddressFileName(address), packet, "S2C");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void clean(SocketAddress address) {
        FileOutputStream stream = WRITERS.remove(getSocketAddressFileName(address));
        try {
            stream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void log(String fileName, Packet packet, String type) throws IOException {
        Path ipPath = getDataDirectory().resolve(fileName + ".txt");

        if (!WRITERS.containsKey(fileName)) {
            Files.deleteIfExists(ipPath);
            Files.createDirectories(ipPath.getParent());
            Files.createFile(ipPath);
            FileOutputStream stream = new FileOutputStream(ipPath.toFile(), true);
            WRITERS.put(fileName, stream);
        }

        FileOutputStream stream = WRITERS.get(fileName);
        stream.write("[%s] ".formatted(type).getBytes(StandardCharsets.UTF_8));
        writePacket(stream, packet);
    }

    private void writePacket(FileOutputStream stream, Packet packet) throws IOException {
        PacketRegistry.PacketInfo info = PacketRegistry.getById(packet.getId());
        if (info == null) {
            LOGGER.at(Level.SEVERE).log("Unknown packet with id %d and class name %s was attempted to be logged.".formatted(packet.getId(), packet.getClass().getCanonicalName()));
            return;
        }
        String packetName = info.name();
        stream.write("%s (%d): ".formatted(packetName, packet.getId()).getBytes(StandardCharsets.UTF_8));
        try {
            stream.write(GSON.toJson(packet).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LOGGER.at(Level.SEVERE).log("Unable to decode packet %s (%d, %s)".formatted(packetName, packet.getId(), packet.getClass().getCanonicalName()), t);
            stream.write("Unable to decode.".getBytes(StandardCharsets.UTF_8));
        }
        stream.write("\n".getBytes(StandardCharsets.UTF_8));
    }

    private String getSocketAddressFileName(SocketAddress address) {
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
