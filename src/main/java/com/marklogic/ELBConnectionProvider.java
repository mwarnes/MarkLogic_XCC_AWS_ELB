package com.marklogic;

import java.io.IOException;
import java.net.*;
import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.marklogic.io.ResourcePool;
import com.marklogic.xcc.Request;
import com.marklogic.xcc.RequestOptions;
import com.marklogic.xcc.Session;
import com.marklogic.xcc.spi.ConnectionErrorAction;
import com.marklogic.xcc.spi.ConnectionProvider;
import com.marklogic.xcc.spi.ServerConnection;
import com.marklogic.xcc.spi.SingleHostAddress;

public class ELBConnectionProvider implements ConnectionProvider, SingleHostAddress {
    private static final int DEFAULT_SOCKET_POOL_SIZE = 64;
    private static final int DEFAULT_SOCKET_BUFFER_SIZE = 128 * 1024;
    private static final String POOL_SIZE_PROPERTY = "xcc.socket.pool.max";
    private static final String SOCKET_SEND_BUFFER_PROPERTY = "xcc.socket.sendbuf";
    private static final String SOCKET_RECV_BUFFER_PROPERTY = "xcc.socket.recvbuf";

    private final int poolSize = Integer.getInteger(POOL_SIZE_PROPERTY, DEFAULT_SOCKET_POOL_SIZE).intValue();
    private static final int socketSendBuffSize = Integer.getInteger(SOCKET_SEND_BUFFER_PROPERTY,
            DEFAULT_SOCKET_BUFFER_SIZE).intValue();
    private static final int socketRecvBuffSize = Integer.getInteger(SOCKET_RECV_BUFFER_PROPERTY,
            DEFAULT_SOCKET_BUFFER_SIZE).intValue();
    private final ResourcePool<SocketAddress, SocketChannel> connectionPool;
    private SocketAddress address;
    private final Logger logger;

    public ELBConnectionProvider(SocketAddress address) {
        logger = Logger.getLogger(ELBConnectionProvider.class.getName());

        logger.info("constructing new ELBConnectionProvider for " + address);

        this.address = address;
        connectionPool = new ResourcePool<SocketAddress, SocketChannel>();
    }

    public ELBConnectionProvider(String host, int port) {
        this(new InetSocketAddress(host, port));
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o == null) return false;
        if(!(o instanceof ELBConnectionProvider)) return false;
        return address.equals(((ELBConnectionProvider)o).getAddress());
    }

    @Override
    public int hashCode() {
        return address.hashCode();
    }

    int getPoolSize() {
        return poolSize;
    }

    public ServerConnection obtainConnection(Session session, Request request, Logger logger) throws IOException {
        if (getLogger(logger).isLoggable(Level.FINE)) {
            getLogger(logger).fine("obtainConnection for " + address);
        }

        SocketChannel channel = connectionPool.get(address);

        if (channel == null) {
            channel = SocketChannel.open(address);
            Socket socket = channel.socket();

            socket.setSendBufferSize(socketSendBuffSize);
            socket.setReceiveBufferSize(socketRecvBuffSize);
            socket.setTcpNoDelay(true);
            socket.setSoLinger(false, 0);
            socket.setKeepAlive(true);

            if (request != null) {
                RequestOptions options = request.getEffectiveOptions();
                int timeout = options.getTimeoutMillis();

                if (timeout >= 0) {
                    socket.setSoTimeout(timeout);
                }
            }

            getLogger(logger).fine("  pool empty, created new connection");
        } else {
            getLogger(logger).fine("  using connection from pool");
        }

        return new ELBConnection(channel, this);
    }

    public void returnConnection(ServerConnection connection, Logger logger) {
        if (getLogger(logger).isLoggable(Level.FINE)) {
            getLogger(logger).fine("returnConnection for " + address +
                    ", expire=" + connection.getTimeoutMillis());
        }

        ByteChannel channel = connection.channel();

        if ((channel == null) || (!(channel instanceof SocketChannel))) {
            getLogger(logger).fine(
                    "channel is not eligible for pooling, dropping");
            return;
        }

        SocketChannel socketChannel = (SocketChannel)channel;
        Socket socket = socketChannel.socket();
        int localPort = socket.getLocalPort() ;

        if (!socketChannel.isOpen() ||
                socket.isInputShutdown() ||
                socket.isOutputShutdown()) {
            if( socketChannel.isOpen()) {
                if (getLogger(logger).isLoggable(Level.FINE)) {
                    getLogger(logger).fine(
                            "channel has been shutdown but not closed: closing and dropping. local-port="
                                    + localPort);
                }
                connection.close();
            } else
                getLogger(logger).fine("channel has been closed, dropping. local-port=" + localPort);
            return;
        }

        long timeoutMillis = connection.getTimeoutMillis();

        if (timeoutMillis <= 0) {
            getLogger(logger).fine("channel has already expired, closing. local-port=" + localPort);
            connection.close();

            return;
        }

        long timeoutTime = connection.getTimeoutTime();

        if (getLogger(logger).isLoggable(Level.FINE)) {
            getLogger(logger).fine("returning socket to pool (" + address + "), timeout time=" + timeoutTime + " local-port=" + localPort);
        }

        connectionPool.put(address, (SocketChannel)channel, timeoutTime);
    }

    public ConnectionErrorAction returnErrorConnection(ServerConnection connection, Throwable exception, Logger logger) {
        if (getLogger(logger).isLoggable(Level.FINE)) {
            getLogger(logger).log(Level.FINE, "error return", exception);
        }
        ByteChannel channel = connection.channel();
        if (channel != null) {
            if (channel.isOpen()) {
                try {
                    channel.close();
                    getLogger(logger).fine("closed error connection");
                } catch (IOException e) {
                    // do nothing, don't care anymore
                }
            } else {
                getLogger(logger).warning("returned error connection is closed, retrying");

                return (ConnectionErrorAction.RETRY);
            }
        }
        if (getLogger(logger).isLoggable(Level.FINE)) {
            getLogger(logger).fine("returning FAIL action");
        }
        return ConnectionErrorAction.FAIL;
    }

    public void shutdown(Logger logger) {
        getLogger(logger).fine("shutting down socket pool provider");

        SocketChannel channel;

        while ((channel = connectionPool.get(address)) != null) {
            try {
                channel.close();
            } catch (IOException e) {
                // do nothing
            }
        }
    }

    @Override
    public String toString() {
        return "address=" + address.toString() + ", pool=" + connectionPool.size(address) + "/" + poolSize;
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
                Instant end = Instant.now();
                long delta = Duration.between(start, end).toMillis();
                getLogger(logger).info("Update complete, time=" + delta + "m/s");
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }

        return (InetSocketAddress)((address instanceof InetSocketAddress) ? address : null);
    }

    public Logger getLogger(Logger clientLogger) {
        return ((clientLogger == null) ? this.logger : clientLogger);
    }

    public void closeExpired(long currTime) {
        connectionPool.closeExpired(currTime);
    }

    public int getPort() {
        InetSocketAddress inetAddress = getAddress();
        return inetAddress == null ? 0 : inetAddress.getPort();
    }

    public String getHostName() {
        InetSocketAddress inetAddress = getAddress();
        return inetAddress == null ? null : inetAddress.getHostName();
    }
}
