package com.marklogic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.util.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import com.marklogic.io.SslByteChannel;
import com.marklogic.xcc.SecurityOptions;
import com.marklogic.xcc.spi.ConnectionProvider;
import com.marklogic.xcc.spi.ServerConnection;

public class ELBSSLConnection implements ServerConnection {
    private final ServerConnection plainConn;
    private final ConnectionProvider provider;
    private final ByteChannel sslChannel;

    public ELBSSLConnection(ServerConnection conn, SecurityOptions securityOptions, ELBSSLConnectionProvider provider,
                         Logger logger) throws IOException {
        if (!(conn.channel() instanceof SocketChannel)) {
            throw new IllegalArgumentException("Underlying channel is not a SocketChannel");
        }

        InetSocketAddress addr = provider.getAddress();
        SSLContext context = securityOptions.getSslContext();
        SSLEngine sslEngine = context.createSSLEngine(addr.getHostName(), addr.getPort());

        this.plainConn = conn;
        this.provider = provider;

        sslEngine.setUseClientMode(true);

        String[] protocols = securityOptions.getEnabledProtocols();
        if (protocols != null) {
            sslEngine.setEnabledProtocols(protocols);
        }

        String[] ciphers = securityOptions.getEnabledCipherSuites();
        if (ciphers != null) {
            sslEngine.setEnabledCipherSuites(ciphers);
        }

        sslChannel = new SslByteChannel(plainConn.channel(), sslEngine, logger);
    }

    public ByteChannel channel() {
        return sslChannel;
    }

    public ConnectionProvider provider() {
        return provider;
    }

    public long getTimeoutMillis() {
        return plainConn.getTimeoutMillis();
    }

    /**
     * @param timeoutMillis
     *            A duration, in milliseconds.
     * @deprecated Use {@link #setTimeoutTime(long)} instead.
     */
    @Deprecated
    public void setTimeoutMillis(long timeoutMillis) {
        plainConn.setTimeoutTime(timeoutMillis);
    }

    public long getTimeoutTime() {
        return plainConn.getTimeoutTime();
    }

    public void setTimeoutTime(long timeMillis) {
        plainConn.setTimeoutTime(timeMillis);
    }

    public void close() {
        try {
            sslChannel.close();
        } catch (IOException e) {
            // ignore
        }
    }

    public boolean isOpen() {
        // FIXME: finish this
        return plainConn.isOpen();
    }
}
