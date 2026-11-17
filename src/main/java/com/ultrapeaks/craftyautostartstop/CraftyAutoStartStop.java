package com.ultrapeaks.craftyautostart;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.slf4j.Logger;
import org.yaml.snakeyaml.Yaml;

import javax.net.ssl.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "craftyautostartstop",
        name = "CraftyAutoStartStop",
        version = "1.0.0",
        description = "Crafty Controller Auto Start/Stop Plugin for Velocity",
        authors = {"NeverArchers"}
)
public class CraftyAutoStart {

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    private String craftyUrl;
    private String craftyToken;
    private boolean ignoreSSL;
    private boolean blockServerSwitching;

    private String msgStarting;
    private String msgWaitingActionbar;
    private String msgReady;
    private String msgSwitchBlocked;

    public static class ManagedServer {
        public String name;
        public String serverId;
        public int idleTimeout;
        public long lastDisconnectTime = -1;

        public ManagedServer(String name, String serverId, int idleTimeout) {
            this.name = name;
            this.serverId = serverId;
            this.idleTimeout = idleTimeout;
        }
    }

    private final Map<String, ManagedServer> managedServers = new ConcurrentHashMap<>();
    private final Set<UUID> connectingPlayers = ConcurrentHashMap.newKeySet();

    @Inject
    public CraftyAutoStart(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        disableSSLChecksIfNeeded();

        CommandManager commandManager = server.getCommandManager();
        CommandMeta commandMeta = commandManager.metaBuilder("craftyautostart")
                .aliases("cas")
                .plugin(this)
                .build();
        commandManager.register(commandMeta, new ReloadCommand());

        server.getScheduler().buildTask(this, this::checkIdleShutdown)
                .repeat(10, TimeUnit.SECONDS)
                .schedule();

        logger.info("CraftyAutoStartStop loaded with multi-server support!");
    }

    @SuppressWarnings("unchecked")
    private void loadConfig() {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
            File configFile = dataDirectory.resolve("config.yml").toFile();
            if (!configFile.exists()) {
                try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                    if (in != null) {
                        Files.copy(in, configFile.toPath());
                    } else {
                        createDefaultConfig(configFile);
                    }
                }
            }

            Yaml yaml = new Yaml();
            Map<String, Object> data;
            try (InputStream input = new FileInputStream(configFile)) {
                data = yaml.load(input);
            }

            this.craftyUrl = (String) data.getOrDefault("crafty-url", "https://127.0.0.1:8443");
            this.craftyToken = (String) data.getOrDefault("crafty-token", "YOUR_API_TOKEN_HERE");
            this.ignoreSSL = (boolean) data.getOrDefault("ignore-ssl-errors", true);
            this.blockServerSwitching = (boolean) data.getOrDefault("block-server-switching-while-waiting", true);

            this.msgStarting = (String) data.getOrDefault("message-starting", "&eStarting {server}. Please wait...");
            this.msgWaitingActionbar = (String) data.getOrDefault("message-waiting-actionbar", "&6⌛ Waiting for {server} to boot...");
            this.msgReady = (String) data.getOrDefault("message-ready", "&a{server} ready! Connecting you now...");
            this.msgSwitchBlocked = (String) data.getOrDefault("message-switch-blocked", "&cYou are already waiting for a server to wake up!");

            managedServers.clear();
            Object serversRaw = data.get("servers");
            if (serversRaw instanceof List) {
                List<?> serverList = (List<?>) serversRaw;
                for (Object obj : serverList) {
                    if (obj instanceof Map) {
                        Map<String, Object> entry = (Map<String, Object>) obj;
                        String name = ((String) entry.get("name")).toLowerCase();
                        String id = (String) entry.get("id");
                        int timeout = (int) entry.getOrDefault("idle-timeout-seconds", 300);
                        managedServers.put(name, new ManagedServer(name, id, timeout));
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Failed to load config.yml for CraftyAutoStartStop", e);
        }
    }

    private void createDefaultConfig(File configFile) throws IOException {
        String defaultConfig = 
                "# Crafty Controller Auto Start Configuration\n" +
                "crafty-url: \"https://127.0.0.1:8443\"\n" +
                "crafty-token: \"YOUR_API_TOKEN_HERE\"\n" +
                "ignore-ssl-errors: true\n" +
                "block-server-switching-while-waiting: true\n\n" +
                "# List of backend servers managed by Crafty\n" +
                "servers:\n" +
                "  - name: \"creative\"\n" +
                "    id: \"YOUR_SERVER_CRAFTY_UUID\"\n" +
                "    idle-timeout-seconds: 300\n\n" +
                "# Messages (Supports color codes & {server} placeholder)\n" +
                "message-starting: \"&eStarting {server}. Please wait...\"\n" +
                "message-waiting-actionbar: \"&6⌛ Waiting for {server} to boot...\"\n" +
                "message-ready: \"&a{server} is ready! Connecting you now...\"\n" +
                "message-switch-blocked: \"&cPlease wait until your current server startup completes!\"\n";
        Files.writeString(configFile.toPath(), defaultConfig);
    }

    private Component parseColor(String input, String targetServer) {
        String formatted = input.replace("{server}", targetServer);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(formatted);
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        RegisteredServer target = event.getResult().getServer().orElse(null);
        if (target == null) return;

        String targetName = target.getServerInfo().getName().toLowerCase();
        Player player = event.getPlayer();

        if (blockServerSwitching && connectingPlayers.contains(player.getUniqueId())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            player.sendMessage(parseColor(msgSwitchBlocked, targetName));
            return;
        }

        ManagedServer ms = managedServers.get(targetName);
        if (ms == null) return;

        target.ping().thenAccept(ping -> {
            ms.lastDisconnectTime = -1;
        }).exceptionally(throwable -> {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            
            if (connectingPlayers.contains(player.getUniqueId())) {
                return null;
            }

            connectingPlayers.add(player.getUniqueId());
            player.sendMessage(parseColor(msgStarting, ms.name));

            startCraftyServer(ms);

            pollServerStatus(player, target, ms);
            return null;
        });
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String name = event.getServer().getServerInfo().getName().toLowerCase();
        ManagedServer ms = managedServers.get(name);
        if (ms != null) {
            ms.lastDisconnectTime = -1;
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        connectingPlayers.remove(event.getPlayer().getUniqueId());
        checkAndMarkIdleAll();
    }

    @Subscribe
    public void onServerSwitch(ServerConnectedEvent event) {
        checkAndMarkIdleAll();
    }

    private void checkAndMarkIdleAll() {
        for (ManagedServer ms : managedServers.values()) {
            Optional<RegisteredServer> targetOpt = server.getServer(ms.name);
            if (targetOpt.isPresent()) {
                RegisteredServer target = targetOpt.get();
                target.ping().thenAccept(ping -> {
                    if (target.getPlayersConnected().isEmpty()) {
                        if (ms.lastDisconnectTime == -1) {
                            ms.lastDisconnectTime = System.currentTimeMillis();
                            logger.info("Server [{}] is empty and online. Idle timer started ({} seconds).", ms.name, ms.idleTimeout);
                        }
                    } else {
                        ms.lastDisconnectTime = -1;
                    }
                }).exceptionally(throwable -> {
                    ms.lastDisconnectTime = -1;
                    return null;
                });
            }
        }
    }

    private void checkIdleShutdown() {
        for (ManagedServer ms : managedServers.values()) {
            if (ms.lastDisconnectTime == -1) continue;

            Optional<RegisteredServer> targetOpt = server.getServer(ms.name);
            if (targetOpt.isEmpty()) continue;

            RegisteredServer target = targetOpt.get();

            target.ping().thenAccept(ping -> {
                if (!target.getPlayersConnected().isEmpty()) {
                    ms.lastDisconnectTime = -1;
                    return;
                }

                long elapsedSeconds = (System.currentTimeMillis() - ms.lastDisconnectTime) / 1000;
                if (elapsedSeconds >= ms.idleTimeout) {
                    logger.info("Idle timeout reached for [{}]. Stopping server via Crafty API.", ms.name);
                    stopCraftyServer(ms);
                    ms.lastDisconnectTime = -1;
                }
            }).exceptionally(throwable -> {
                ms.lastDisconnectTime = -1;
                return null;
            });
        }
    }

    private void pollServerStatus(Player player, RegisteredServer target, ManagedServer ms) {
        server.getScheduler().buildTask(this, () -> {
            target.ping().thenAccept(ping -> {
                connectingPlayers.remove(player.getUniqueId());
                player.sendMessage(parseColor(msgReady, ms.name));
                player.createConnectionRequest(target).fireAndForget();
            }).exceptionally(throwable -> {
                if (player.isActive() && connectingPlayers.contains(player.getUniqueId())) {
                    player.sendActionBar(parseColor(msgWaitingActionbar, ms.name));
                    pollServerStatus(player, target, ms);
                } else {
                    connectingPlayers.remove(player.getUniqueId());
                }
                return null;
            });
        }).delay(3, TimeUnit.SECONDS).schedule();
    }

    private void startCraftyServer(ManagedServer ms) {
        sendCraftyAction(ms, "start_server");
    }

    private void stopCraftyServer(ManagedServer ms) {
        sendCraftyAction(ms, "stop_server");
    }

    private void sendCraftyAction(ManagedServer ms, String action) {
        server.getScheduler().buildTask(this, () -> {
            try {
                String endpoint = craftyUrl + "/api/v2/servers/" + ms.serverId + "/action/" + action;
                URL url = new URL(endpoint);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + craftyToken);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }

                int responseCode = conn.getResponseCode();
                
                if (responseCode >= 400) {
                    InputStream es = conn.getErrorStream();
                    if (es != null) {
                        String errorResponse = new String(es.readAllBytes(), StandardCharsets.UTF_8);
                        logger.error("Crafty API Error [{}] on {}: Response Code {} - {}", action, ms.name, responseCode, errorResponse);
                    } else {
                        logger.error("Crafty API Error [{}] on {}: Response Code {}", action, ms.name, responseCode);
                    }
                } else {
                    logger.info("Crafty API call [{}] for {} returned status code: {}", action, ms.name, responseCode);
                }

                conn.disconnect();
            } catch (Exception e) {
                logger.error("Failed to execute Crafty action: " + action + " for " + ms.name, e);
            }
        }).schedule();
    }

    private void disableSSLChecksIfNeeded() {
        if (!ignoreSSL) return;
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            };
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            logger.error("Failed to disable SSL checks", e);
        }
    }

    private class ReloadCommand implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                loadConfig();
                disableSSLChecksIfNeeded();
                invocation.source().sendMessage(parseColor("&aCraftyAutoStartStop configuration reloaded successfully!", ""));
            } else {
                invocation.source().sendMessage(parseColor("&cUsage: /craftyautostartstop reload", ""));
            }
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("craftyautostartstop.admin");
        }
    }
}
