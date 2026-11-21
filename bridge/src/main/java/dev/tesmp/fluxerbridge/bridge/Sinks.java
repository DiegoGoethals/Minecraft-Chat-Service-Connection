package dev.tesmp.fluxerbridge.bridge;

import dev.tesmp.fluxerbridge.protocol.Message;

/** Small interfaces so the {@link Router} can be tested without network. */
public final class Sinks {
	private Sinks() {
	}

	/** Connected Minecraft servers. */
	public interface Minecraft {
		boolean isConnected(String serverId);

		void send(String serverId, Message message);
	}

	/** Posting to Fluxer. Implementations must not block the caller. */
	public interface Fluxer {
		/** Post as the bot itself. */
		void post(BridgeConfig.Link link, String content);

		/**
		 * Post through the link's webhook under a custom name and avatar.
		 * Falls back to posting {@code fallbackContent} as the bot when the
		 * webhook cannot be used.
		 */
		void postAs(BridgeConfig.Link link, String username, String avatarUrl, String content, String fallbackContent);

		/** True for webhooks this bridge created, so their messages are never relayed back. */
		boolean isOwnWebhook(String webhookId);
	}
}
