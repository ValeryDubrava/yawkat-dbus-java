package at.yawk.dbus.protocol;

import at.yawk.dbus.protocol.auth.AuthClient;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollDomainSocketChannel;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.StringReader;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * @author yawkat
 */
@Slf4j
@Getter
@Setter
public class DbusConnector {
    private final Bootstrap bootstrap;
    private Channel channel;
    private UUID guid;

    public DbusConnector() {
        bootstrap = new Bootstrap();
        bootstrap.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                ch.config().setAutoRead(false);
            }
        });
    }

    /**
     * Connect to the dbus server at the given {@link SocketAddress}.
     */
    public void connect(SocketAddress address) throws Exception {
        if (Epoll.isAvailable()) {
            bootstrap.group(new EpollEventLoopGroup());
            if (address instanceof DomainSocketAddress) {
                bootstrap.channel(EpollDomainSocketChannel.class);
            } else {
                bootstrap.channel(EpollSocketChannel.class);
            }
        } else {
            bootstrap.group(new NioEventLoopGroup());
            bootstrap.channel(NioSocketChannel.class);
        }

        channel = bootstrap.connect(address).sync().channel();
        AuthClient authClient = new AuthClient();
        if (LoggingInboundAdapter.isEnabled()) {
            channel.pipeline().addLast(new LoggingInboundAdapter());
        }

        channel.pipeline().addLast("auth", authClient);
        channel.config().setAutoRead(true);

        ChannelFuture completionPromise = authClient.startAuth(channel);
        completionPromise.sync();
        channel.pipeline().remove("auth");
    }

    public void connect(DbusAddress address) throws Exception {
        log.info("Connecting to dbus server at {}", address);

        if (address.hasProperty("guid")) {
            guid = DbusUtil.parseUuid(address.getProperty("guid"));
        }

        switch (address.getProtocol()) {
        case "unix":
            if (address.hasProperty("path")) {
                connect(new DomainSocketAddress(address.getProperty("path")));
            } else if (address.hasProperty("abstract")) {
                String path = address.getProperty("abstract");

                // replace leading slash with \0 for abstract socket
                if (!path.startsWith("/")) { throw new IllegalArgumentException("Illegal abstract path " + path); }
                path = '\0' + path;

                connect(new DomainSocketAddress(path));
            } else {
                throw new IllegalArgumentException("Neither path nor abstract given in dbus url");
            }
            break;
        default:
            throw new UnsupportedOperationException("Unsupported protocol " + address.getProtocol());
        }
    }

    public void connectUser() throws Exception {
        String machineId = new String(Files.readAllBytes(Paths.get("/etc/machine-id"))).trim();
        String response = DbusUtil.callCommand("dbus-launch", "--autolaunch", machineId);
        Properties properties = new Properties();
        properties.load(new StringReader(response));
        String address = properties.getProperty("DBUS_SESSION_BUS_ADDRESS");
        connect(DbusAddress.parse(address));
    }

    public void connectSystem() throws Exception {
        // this is the default system socket location defined in dbus
        connect(DbusAddress.fromUnixSocket(Paths.get("/run/dbus/system_bus_socket")));
    }

    private static <V> CompletionStage<V> nettyFutureToStage(Future<V> future) {
        CompletableFuture<V> stage = new CompletableFuture<>();
        future.addListener(new GenericFutureListener<Future<V>>() {
            @Override
            public void operationComplete(Future<V> future) throws Exception {
                try {
                    stage.complete(future.get());
                } catch (ExecutionException e) {
                    stage.completeExceptionally(e.getCause());
                }
            }
        });
        return stage;
    }
}