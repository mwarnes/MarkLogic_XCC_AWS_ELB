package com.marklogic.aws;

import java.io.IOException;
import java.nio.channels.ByteChannel;

import com.marklogic.xcc.spi.ConnectionProvider;
import com.marklogic.xcc.spi.ServerConnection;

public class ELBConnection implements ServerConnection {
    private final ByteChannel channel;
    private final ConnectionProvider provider;
    private long timeoutTime = 0;

    public ELBConnection(ByteChannel channel, ConnectionProvider provider) {
        this.channel = channel;
        this.provider = provider;
    }

    public ByteChannel channel() {
        return channel;
    }

    public ConnectionProvider provider() {
        return provider;
    }

    public long getTimeoutMillis() {
        long millis = timeoutTime - System.currentTimeMillis();

        return (millis < 0) ? 0 : millis;
    }

    /**
     * Set timeout as a number of milliseconds in the future.
     *
     * @param timeoutMillis
     *            A number of miliseconds.
     * @deprecated Use {@link #setTimeoutTime(long)} instead.
     */
    @Deprecated
    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutTime = System.currentTimeMillis() + timeoutMillis;
    }

    public long getTimeoutTime() {
        return timeoutTime;
    }

    public void setTimeoutTime(long timeMillis) {
        this.timeoutTime = timeMillis;
    }

    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            // ignore
        }
    }

    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public String toString() {
        return "ELBConnection [provider: " + provider.toString() + "]";
    }
}
