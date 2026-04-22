package com.saunhardy.crfp.fakeplayer;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * A {@link Connection} that is never wired to a real socket. Used as the network backing
 * for a fake {@link net.minecraft.server.level.ServerPlayer} that we place into the player
 * list. All outbound packets are dropped by the embedded channel; nothing ever inbounds.
 *
 * The vanilla Connection class keeps {@code channel} and {@code address} as private fields
 * that are normally populated by Netty when a real socket hooks up. Here we populate them
 * reflectively so the instance is well-formed enough for the rest of the server to treat it
 * like any other connection.
 */
public final class DummyConnection extends Connection {
    private static final Field CHANNEL_FIELD;
    private static final Field ADDRESS_FIELD;

    static {
        Field channelField = null;
        Field addressField = null;
        for (Field f : Connection.class.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (channelField == null && Channel.class.isAssignableFrom(t)) {
                f.setAccessible(true);
                channelField = f;
            } else if (addressField == null && SocketAddress.class.isAssignableFrom(t)) {
                f.setAccessible(true);
                addressField = f;
            }
        }
        if (channelField == null || addressField == null) {
            throw new ExceptionInInitializerError(
                    "DummyConnection could not locate channel/address fields on Connection");
        }
        CHANNEL_FIELD = channelField;
        ADDRESS_FIELD = addressField;
    }

    public DummyConnection() {
        super(PacketFlow.SERVERBOUND);
        try {
            CHANNEL_FIELD.set(this, new EmbeddedChannel());
            ADDRESS_FIELD.set(this, new InetSocketAddress("127.0.0.1", 0));
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize DummyConnection", e);
        }
    }

    @Override
    public void send(Packet<?> packet) {
        // no-op
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        // no-op
    }

    @Override
    public boolean isConnected() {
        return true;
    }
}
