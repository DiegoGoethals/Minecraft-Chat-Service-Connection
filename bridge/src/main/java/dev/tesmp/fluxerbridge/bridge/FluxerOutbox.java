package dev.tesmp.fluxerbridge.bridge;

import dev.tesmp.fluxerbridge.bridge.fluxer.FluxerApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Posts to Fluxer from one background thread, in order. The API client
 * handles rate limits by waiting, so one slow channel just delays the queue.
 * If the queue backs up past 500 messages the oldest are dropped.
 */
public final class FluxerOutbox implements Sinks.Fluxer {
	private static final Logger LOG = LoggerFactory.getLogger("FluxerOutbox");

	private final FluxerApi api;
	private final String botUserId;
	private final ThreadPoolExecutor exec;
	private final Map<String, FluxerApi.Webhook> webhooks = new ConcurrentHashMap<>();
	private final Set<String> ownWebhookIds = ConcurrentHashMap.newKeySet();
	/** Channels where the webhook can't be used (usually missing Manage Webhooks). */
	private final Set<String> webhookUnavailable = ConcurrentHashMap.newKeySet();

	public FluxerOutbox(FluxerApi api, String botUserId) {
		this.api = api;
		this.botUserId = botUserId;
		this.exec = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(500),
				r -> {
					Thread t = new Thread(r, "fluxer-outbox");
					t.setDaemon(true);
					return t;
				},
				new ThreadPoolExecutor.DiscardOldestPolicy());
	}

	/** Look up or create webhooks up front so their ids are known before any message arrives. */
	public void prepare(List<BridgeConfig.Link> links) {
		for (BridgeConfig.Link link : links) {
			if (link.toFluxer && link.useWebhook()) {
				exec.execute(() -> webhookFor(link));
			}
		}
	}

	@Override
	public void post(BridgeConfig.Link link, String content) {
		exec.execute(() -> {
			try {
				api.createMessage(link.channelId, TextFormat.truncate(content, TextFormat.FLUXER_MAX_LENGTH));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				LOG.warn("Could not post to channel {}: {}", link.channelId, e.getMessage());
			}
		});
	}

	@Override
	public void postAs(BridgeConfig.Link link, String username, String avatarUrl, String content, String fallbackContent) {
		exec.execute(() -> {
			String text = TextFormat.truncate(content, TextFormat.FLUXER_MAX_LENGTH);
			for (int attempt = 0; attempt < 2; attempt++) {
				FluxerApi.Webhook webhook = webhookFor(link);
				if (webhook == null) {
					break;
				}
				try {
					api.executeWebhook(webhook, username, avatarUrl, text);
					return;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (FluxerApi.FluxerException e) {
					if (e.status() == 404 || e.status() == 401) {
						LOG.info("Webhook in channel {} is gone, creating a new one", link.channelId);
						webhooks.remove(key(link));
						continue;
					}
					LOG.warn("Webhook post failed in channel {}: {}", link.channelId, e.getMessage());
					break;
				} catch (Exception e) {
					LOG.warn("Webhook post failed in channel {}: {}", link.channelId, e.getMessage());
					break;
				}
			}
			try {
				api.createMessage(link.channelId, TextFormat.truncate(fallbackContent, TextFormat.FLUXER_MAX_LENGTH));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				LOG.warn("Could not post to channel {}: {}", link.channelId, e.getMessage());
			}
		});
	}

	@Override
	public boolean isOwnWebhook(String webhookId) {
		return ownWebhookIds.contains(webhookId);
	}

	public void close() {
		exec.shutdown();
		try {
			exec.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Runs on the outbox thread. */
	private FluxerApi.Webhook webhookFor(BridgeConfig.Link link) {
		String key = key(link);
		if (webhookUnavailable.contains(key)) {
			return null;
		}
		FluxerApi.Webhook cached = webhooks.get(key);
		if (cached != null) {
			return cached;
		}
		try {
			FluxerApi.Webhook webhook = api.getOrCreateWebhook(link.channelId, link.webhookName, botUserId);
			webhooks.put(key, webhook);
			ownWebhookIds.add(webhook.id());
			return webhook;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			webhookUnavailable.add(key);
			LOG.warn("Can't use a webhook in channel {} ({}). Posting as the bot instead. "
					+ "Give the bot the Manage Webhooks permission and restart the bridge, "
					+ "or set \"style\": \"bot\" on this link to always post as the bot.", link.channelId, e.getMessage());
			return null;
		}
	}

	private static String key(BridgeConfig.Link link) {
		return link.channelId + "\n" + link.webhookName;
	}
}
