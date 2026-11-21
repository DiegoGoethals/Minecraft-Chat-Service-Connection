package dev.tesmp.fluxerbridge.bridge;

import com.google.gson.JsonObject;
import dev.tesmp.fluxerbridge.bridge.fluxer.FluxerApi;
import dev.tesmp.fluxerbridge.bridge.fluxer.FluxerGateway;
import dev.tesmp.fluxerbridge.bridge.fluxer.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

/**
 * Starts the bridge:
 * <ol>
 *   <li>read the config (path from the first argument, {@code BRIDGE_CONFIG}, or {@code bridge.json})</li>
 *   <li>find the Fluxer API and check the bot token</li>
 *   <li>open the WebSocket server for Minecraft servers</li>
 *   <li>connect to the Fluxer Gateway</li>
 * </ol>
 */
public final class Main {
	private static final Logger LOG = LoggerFactory.getLogger("FluxerBridge");

	private Main() {
	}

	public static void main(String[] args) throws Exception {
		Path configPath = Path.of(args.length > 0 ? args[0]
				: System.getenv().getOrDefault("BRIDGE_CONFIG", "bridge.json"));
		LOG.info("Fluxer Minecraft Bridge {} starting, config: {}", Version.get(), configPath.toAbsolutePath());

		if (!Files.exists(configPath)) {
			LOG.error("Config file {} not found. Copy docs/bridge.example.json and fill it in.", configPath.toAbsolutePath());
			System.exit(2);
		}
		BridgeConfig config;
		try {
			config = BridgeConfig.load(configPath);
		} catch (IllegalArgumentException | IOException e) {
			LOG.error(e.getMessage());
			System.exit(2);
			return;
		}

		HttpClient http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();

		FluxerApi.Endpoints endpoints = retry("Fluxer discovery", () -> FluxerApi.discover(http, config.fluxer.instance));
		FluxerApi api = new FluxerApi(http, endpoints.apiPublic(), config.fluxer.token);
		JsonObject me;
		try {
			me = retry("Fluxer login", api::currentUser);
		} catch (FluxerApi.FluxerException e) {
			LOG.error("Fluxer refused the bot token: {}", e.getMessage());
			System.exit(3);
			return;
		}
		String botId = me.get("id").getAsString();
		LOG.info("Bot account: {} ({})", me.get("username").getAsString(), botId);

		FluxerOutbox outbox = new FluxerOutbox(api, botId);
		Router router = new Router(config, outbox);
		router.setSelfUserId(botId);
		outbox.prepare(config.links);

		MinecraftHub hub = new MinecraftHub(new InetSocketAddress(config.listen.host, config.listen.port), config, router);
		router.setMinecraft(hub);
		hub.start();

		FluxerGateway gateway = new FluxerGateway(http, endpoints.gateway(), config.fluxer.token, router);
		gateway.start();

		CountDownLatch stopped = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.info("Shutting down");
			gateway.close();
			hub.shutdown();
			outbox.close();
			stopped.countDown();
		}, "shutdown"));
		stopped.await();
	}

	private interface Call<T> {
		T call() throws Exception;
	}

	/** Retries network failures forever (the bridge may start before the network). API errors are thrown. */
	private static <T> T retry(String what, Call<T> call) throws Exception {
		long delay = 2000;
		while (true) {
			try {
				return call.call();
			} catch (FluxerApi.FluxerException e) {
				if (e.status() == 401 || e.status() == 403) {
					throw e;
				}
				LOG.warn("{} failed: {}. Retrying in {}s", what, e.getMessage(), delay / 1000);
			} catch (IOException e) {
				LOG.warn("{} failed: {}. Retrying in {}s", what, e.toString(), delay / 1000);
			}
			Thread.sleep(delay);
			delay = Math.min(60_000, delay * 2);
		}
	}
}
