package dev.tesmp.fluxerbridge.mod;

import dev.tesmp.fluxerbridge.protocol.EventKind;
import dev.tesmp.fluxerbridge.protocol.Message;
import dev.tesmp.fluxerbridge.protocol.client.BridgeClient;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Server-side entrypoint. Players don't need to install anything.
 */
public final class FluxerBridgeMod implements DedicatedServerModInitializer {
	public static final String MOD_ID = "fluxerbridge";
	public static final Logger LOG = LoggerFactory.getLogger("FluxerBridge");

	private static FluxerBridgeMod instance;

	private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("fluxerbridge.json");
	private volatile ModConfig config = new ModConfig();
	private volatile BridgeClient client;
	private volatile MinecraftServer server;

	public static FluxerBridgeMod get() {
		return instance;
	}

	@Override
	public void onInitializeServer() {
		instance = this;
		try {
			config = ModConfig.load(configPath);
		} catch (IOException e) {
			LOG.error("Could not load {}: {}. The bridge stays off until it is fixed and reloaded.", configPath, e.getMessage());
			config = null;
		}

		MinecraftEvents.register(this);
		CommandRegistrationCallback.EVENT.register(BridgeCommand::register);

		ServerLifecycleEvents.SERVER_STARTED.register(started -> {
			this.server = started;
			connect();
			ModConfig cfg = config;
			if (cfg != null && cfg.send.serverStatus) {
				sendEvent(EventKind.SERVER_START, EventKind.TAG_STATUS, null, null, "The server is online");
			}
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> {
			ModConfig cfg = config;
			if (cfg != null && cfg.send.serverStatus) {
				sendEvent(EventKind.SERVER_STOP, EventKind.TAG_STATUS, null, null, "The server is shutting down");
			}
			disconnect();
			this.server = null;
		});
	}

	// --------------------------------------------------------------- lifecycle

	private synchronized void connect() {
		ModConfig cfg = config;
		if (cfg == null) {
			return;
		}
		String problem = cfg.problem();
		if (problem != null) {
			LOG.warn("Fluxer bridge is not connecting: {} (edit {} and run /fluxerbridge reload)", problem, configPath);
			return;
		}
		BridgeClient newClient = new BridgeClient(URI.create(cfg.bridgeUrl), cfg.serverId, cfg.secret, version(), new BridgeClient.Listener() {
			@Override
			public void onMessage(Message message) {
				onBridgeMessage(message);
			}
		});
		client = newClient;
		newClient.start();
	}

	private synchronized void disconnect() {
		BridgeClient old = client;
		client = null;
		if (old != null) {
			old.close();
		}
	}

	/** Re-reads the config and reconnects. @return an error message, or null on success */
	public synchronized String reload() {
		ModConfig loaded;
		try {
			loaded = ModConfig.load(configPath);
		} catch (IOException e) {
			return e.getMessage();
		}
		disconnect();
		config = loaded;
		if (server != null) {
			connect();
		}
		return loaded.problem();
	}

	// ---------------------------------------------------------------- outgoing

	public ModConfig config() {
		return config;
	}

	public boolean isConnected() {
		BridgeClient c = client;
		return c != null && c.isConnected();
	}

	public void sendChat(ServerPlayer player, String text) {
		BridgeClient c = client;
		if (c != null && !text.isBlank()) {
			c.send(new Message.Chat(UUID.randomUUID().toString(), EventKind.TAG_CHAT,
					player.getName().getString(), player.getStringUUID(), text, null));
		}
	}

	public void sendEvent(String kind, String tag, String player, String playerId, String text) {
		BridgeClient c = client;
		if (c != null && text != null && !text.isBlank()) {
			c.send(new Message.ServerEvent(UUID.randomUUID().toString(), kind, tag, player, playerId, text));
		}
	}

	// ---------------------------------------------------------------- incoming

	/** Runs on the bridge client thread; hop to the server thread before touching the game. */
	private void onBridgeMessage(Message message) {
		MinecraftServer srv = server;
		if (srv == null) {
			return;
		}
		switch (message) {
			case Message.Chat chat -> {
				ModConfig cfg = config;
				if (cfg == null || !cfg.receive.enabled) {
					return;
				}
				var line = ChatFormatter.format(cfg.receive, chat);
				srv.execute(() -> srv.getPlayerList().broadcastSystemMessage(line, false));
			}
			case Message.StatusRequest request -> srv.execute(() -> {
				List<String> players = srv.getPlayerList().getPlayers().stream()
						.map(p -> p.getName().getString())
						.toList();
				BridgeClient c = client;
				if (c != null) {
					c.send(new Message.Status(request.requestId(), players, srv.getPlayerList().getMaxPlayers(), srv.getServerVersion()));
				}
			});
			default -> {
			}
		}
	}

	private static String version() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("dev");
	}
}
