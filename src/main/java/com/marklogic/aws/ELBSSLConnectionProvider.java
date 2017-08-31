package com.marklogic.aws;

import com.marklogic.io.ResourcePool;
import com.marklogic.io.SslByteChannel;
import com.marklogic.xcc.Request;
import com.marklogic.xcc.SecurityOptions;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.spi.ConnectionErrorAction;
import com.marklogic.xcc.spi.ConnectionProvider;
import com.marklogic.xcc.spi.ServerConnection;
import com.marklogic.xcc.spi.SingleHostAddress;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ByteChannel;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ELBSSLConnectionProvider implements ConnectionProvider, SingleHostAddress {
    private SocketAddress address;
    private final SecurityOptions securityOptions;
    private ELBConnectionProvider socketProvider;
    private final ResourcePool<SocketAddress, ServerConnection> sslPool;
    private final Logger logger;

    public ELBSSLConnectionProvider(SocketAddress address, SecurityOptions options) throws NoSuchAlgorithmException,
            KeyManagementException {
        logger = Logger.getLogger(ConnectionProvider.class.getName());

        logger.info("constructing new ELBSSLConnectionProvider for " + address);

        this.address = address;
        this.socketProvider = new ELBConnectionProvider(address);
        this.securityOptions = options;

        sslPool = new ResourcePool<SocketAddress, ServerConnection>();
    }

    public ELBSSLConnectionProvider(String host, int port, SecurityOptions options) throws NoSuchAlgorithmException,
            KeyManagementException {
        this(new InetSocketAddress(host, port),options);
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o == null) return false;
        if(!(o instanceof ELBSSLConnectionProvider)) return false;
        return address.equals(((ELBSSLConnectionProvider)o).getAddress()) &&
                securityOptions.equals(((ELBSSLConnectionProvider)o).getSecurityOptions());
    }

    @Override
    public int hashCode() {
        return address.hashCode() + securityOptions.hashCode();
    }

    public InetSocketAddress getAddress() {
        Instant start = Instant.now();
        try {
            // Get cached IP address for hostname
            InetSocketAddress ia = (InetSocketAddress) address;
            // Get current IP address for hostname
            InetAddress inetAddress = InetAddress.getByName(ia.getHostName());
            // If the IP addresses differ then update the cached addressed to reflect changes
            if (!ia.getAddress().getHostAddress().equals(inetAddress.getHostAddress())) {
                getLogger(logger).info("Cached InetAddress " + ia.getAddress().getHostAddress());
                getLogger(logger).info("Current InetAddress " + inetAddress.getHostAddress());
                getLogger(logger).info("Current and Cached IP Addresses do not match... updating...");
                address = new InetSocketAddress(inetAddress.getHostAddress(), ia.getPort());
                // Update the underlying Connectin Provider
                socketProvider = new ELBConnectionProvider(address);
                Instant end = Instant.now();
                long delta = Duration.between(start, end).toMillis();
                getLogger(logger).info("Update complete, time=" + delta + "m/s");
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        return (InetSocketAddress)((address instanceof InetSocketAddress) ? address : null);
    }

    public SecurityOptions getSecurityOptions() {
        return securityOptions;
    }

    public ServerConnection obtainConnection(Session session, Request request, Logger logger) throws IOException {
        ServerConnection conn = sslPool.get(address);

        if (conn != null) {
            return conn;
        }

        conn = socketProvider.obtainConnection(session, request, logger);

        return new ELBSSLConnection(conn, securityOptions,  this, logger);
    }

    public void returnConnection(ServerConnection connection, Logger logger) {
        if (getLogger(logger).isLoggable(Level.FINE)) {
            getLogger(logger).fine("returnConnection for " + address + ", expire=" + connection.getTimeoutMillis());
        }

        ByteChannel channel = connection.channel();

        if ((channel == null) || (!(channel instanceof SslByteChannel))) {
            getLogger(logger).fine("channel is not eligible for pooling, dropping");
            try {
                channel.close();
            } catch (IOException e) {
                getLogger(logger).fine("unable to close channel");
            }
            return;
        }

        SslByteChannel socketChannel = (SslByteChannel)channel;
        if (!socketChannel.isOpen()) {
            getLogger(logger).fine("channel has been closed, dropping");
            return;
        }

        long timeoutMillis = connection.getTimeoutMillis();

        if (timeoutMillis <= 0) {
            getLogger(logger).fine("channel has already expired, closing");

            connection.close();

            return;
        }

        long timeoutTime = connection.getTimeoutTime();

        if (getLogger(logger).isLoggable(Level.FINE)) {
            getLogger(logger).fine("returning socket to pool (" + address + "), timeout time=" + timeoutTime);
        }

        sslPool.put(address, connection, timeoutTime);
    }

    public ConnectionErrorAction returnErrorConnection(ServerConnection connection, Throwable exception, Logger logger) {
        getLogger(logger).log(Level.FINE, "error return", exception);

        ByteChannel channel = connection.channel();

        if (channel != null) {
            if (channel.isOpen()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    // do nothing, don't care anymore
                }
            } else {
                getLogger(logger).warning("returned error connection is closed, retrying");

                return (ConnectionErrorAction.RETRY);
            }
        }

        getLogger(logger).fine("returning FAIL action");

        return ConnectionErrorAction.FAIL;
    }

    public void shutdown(Logger logger) {
        getLogger(logger).fine("shutting down socket pool provider");

        ServerConnection conn;

        while ((conn = sslPool.get(address)) != null) {
            conn.close();
        }

        socketProvider.shutdown(logger);
    }

    // ---------------------------------------------------------------

    @Override
    public String toString() {
        // TODO: Add more SSL info here?
        return "ELBSSLConnection address=" + address.toString() + ", pool=" + sslPool.size(address) + "/"
                + socketProvider.getPoolSize();
    }

    // --------------------------------------------------------

    private Logger getLogger(Logger clientLogger) {
        return ((clientLogger == null) ? this.logger : clientLogger);
    }

    public void closeExpired(long currTime) {
        sslPool.closeExpired(currTime);
    }

    @Override
    public int getPort() {
        InetSocketAddress inetAddress = getAddress();
        return inetAddress == null ? 0 : inetAddress.getPort();
    }

    @Override
    public String getHostName() {
        InetSocketAddress inetAddress = getAddress();
        return inetAddress == null ? null : inetAddress.getHostName();
    }
}
