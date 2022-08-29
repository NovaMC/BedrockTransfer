/*
 * Copyright (c) 2019-2020 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/GeyserConnect
 */

package org.geysermc.connect;

import com.nukkitx.protocol.bedrock.*;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.Getter;
import org.geysermc.connect.proxy.GeyserProxyBootstrap;
import org.geysermc.connect.utils.GeyserConnectFileUtils;
import org.geysermc.connect.utils.Logger;
import org.geysermc.connect.utils.ServerInfo;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.MinecraftProtocol;
import org.geysermc.geyser.util.FileUtils;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;

public class MasterServer {

    private BedrockServer bdServer;

    @Getter
    private final ServerInfo serverInfo;

    @Getter
    private boolean shuttingDown = false;

    @Getter
    private static MasterServer instance;

    @Getter
    private final Logger logger;

    private final ScheduledExecutorService scheduledThreadPool;

    @Getter
    private GeyserProxyBootstrap geyserProxy;

    @Getter
    private GeyserConnectConfig geyserConnectConfig;

    @Getter
    private final DefaultEventLoopGroup eventLoopGroup = new DefaultEventLoopGroup(new DefaultThreadFactory("Geyser player thread"));

    public MasterServer() {
        instance = this;

        logger = new Logger();

        try {
            File configFile = GeyserConnectFileUtils.fileOrCopiedFromResource(new File("config.yml"), "config.yml", (x) -> x);
            this.geyserConnectConfig = FileUtils.loadConfig(configFile, GeyserConnectConfig.class);
        } catch (IOException ex) {
            logger.severe("Failed to read/create config.yml! Make sure it's up to date and/or readable+writable!", ex);
            ex.printStackTrace();
        }

        logger.setDebug(geyserConnectConfig.isDebugMode());

        // As this is only used for server querying, we don't need to handle many threads
        this.scheduledThreadPool = Executors.newSingleThreadScheduledExecutor();

        // Grab serverinfo from config defaults
        serverInfo = geyserConnectConfig.getServerInfo();

        // Try to sync the server info
        updateSessionInfo(serverInfo);

        // Schedule update task
        scheduledThreadPool.scheduleWithFixedDelay(() -> updateSessionInfo(serverInfo),
                geyserConnectConfig.getUpdateInterval(), geyserConnectConfig.getUpdateInterval(), TimeUnit.SECONDS);

        // Start a timer to keep the thread running
        Timer timer = new Timer();
        TimerTask task = new TimerTask() { public void run() { } };
        timer.scheduleAtFixedRate(task, 0L, 1000L);

        start(geyserConnectConfig.getPort());

        logger.start();
    }

    private void start(int port) {
        logger.info("Starting...");

        InetSocketAddress bindAddress = new InetSocketAddress(geyserConnectConfig.getAddress(), port);
        bdServer = new BedrockServer(bindAddress);

        bdServer.setHandler(new BedrockServerEventHandler() {
            @Override
            public boolean onConnectionRequest(@NotNull InetSocketAddress address) {
                return true; // Connection will be accepted
            }

            @Override
            public BedrockPong onQuery(@NotNull InetSocketAddress address) {
                String subMotd = serverInfo.getSubmotd();
                if (subMotd == null || subMotd.isEmpty()) {
                    subMotd = "GeyserConnect";
                }
                String motd = serverInfo.getMotd();
                boolean swapMotd = geyserConnectConfig.isSwapMotd();

                BedrockPong bdPong = new BedrockPong();
                // Static info
                bdPong.setEdition("MCPE");
                bdPong.setGameType("Survival");
                bdPong.setIpv4Port(port);
                // Data from serverInfo
                bdPong.setMotd(!swapMotd ? motd : subMotd);
                bdPong.setSubMotd(!swapMotd ? subMotd : motd);
                bdPong.setPlayerCount(serverInfo.getPlayers());
                bdPong.setMaximumPlayerCount(serverInfo.getMaxPlayers());
                // Set version info
                bdPong.setProtocolVersion(MinecraftProtocol.DEFAULT_BEDROCK_CODEC.getProtocolVersion());
                bdPong.setVersion(MinecraftProtocol.DEFAULT_BEDROCK_CODEC.getMinecraftVersion());

                return bdPong;
            }

            @Override
            public void onSessionCreation(@NotNull BedrockServerSession session) {
                session.setPacketHandler(new PacketHandler(session, instance));
            }
        });

        // Start server up
        bdServer.bind().join();

        // Create the Geyser instance
        createGeyserProxy();

        logger.info("Server started on " + geyserConnectConfig.getAddress() + ":" + port);
    }

    // Taken and adapted from MCXboxBroadcast
    // https://github.com/rtm516/MCXboxBroadcast/blob/master/bootstrap/standalone/src/main/java/com/rtm516/mcxboxbroadcast/bootstrap/standalone/StandaloneMain.java
    private void updateSessionInfo(ServerInfo serverInfo) {
        if (geyserConnectConfig.isQueryServer()) {
            BedrockClient client = null;
            try {
                InetSocketAddress bindAddress = new InetSocketAddress("0.0.0.0", 0);
                client = new BedrockClient(bindAddress);

                client.bind().join();

                InetSocketAddress addressToPing = new InetSocketAddress(serverInfo.getIp(), serverInfo.getPort());
                BedrockPong pong = client.ping(addressToPing, 1500, TimeUnit.MILLISECONDS).get();

                // Update the session information
                serverInfo.setMotd(pong.getMotd());
                serverInfo.setSubmotd(pong.getSubMotd());
                serverInfo.setPlayers(pong.getPlayerCount());
                serverInfo.setMaxPlayers(pong.getMaximumPlayerCount());

                logger.debug("Updated server info");
            } catch (InterruptedException | ExecutionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    logger.error("Timed out while trying to ping server");
                } else {
                    logger.error("Failed to ping server", e);
                }
                // Set session info back to default
                serverInfo.setMotd(geyserConnectConfig.getServerInfo().getMotd());
                serverInfo.setSubmotd(geyserConnectConfig.getServerInfo().getSubmotd());
                serverInfo.setPlayers(geyserConnectConfig.getServerInfo().getPlayers());
                serverInfo.setMaxPlayers(geyserConnectConfig.getServerInfo().getMaxPlayers());
            } finally {
                if (client != null) {
                    client.close();
                }
            }
        }
    }

    public void shutdown() {
        shuttingDown = true;
        bdServer.close();

        shutdownGeyserProxy();

        scheduledThreadPool.shutdown();
        System.exit(0);
    }

    public void createGeyserProxy() {
        if (geyserProxy == null) {
            // Make sure Geyser doesn't start the listener
            GeyserImpl.setShouldStartListener(false);

            this.geyserProxy = new GeyserProxyBootstrap();
            geyserProxy.onEnable();
        }
    }

    public void shutdownGeyserProxy() {
        if (geyserProxy != null) {
            geyserProxy.onDisable();
            geyserProxy = null;
        }
    }
}
