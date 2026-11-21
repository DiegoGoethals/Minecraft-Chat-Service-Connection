# Mod ↔ bridge protocol

WebSocket, one JSON object per text frame, with a `type` field. The code is in [`protocol/`](../protocol/src/main/java/dev/tesmp/fluxerbridge/protocol/Message.java). Current version: **1** (`Protocol.VERSION`).

## Handshake

1. The mod connects to `bridgeUrl` and sends:
   ```json
   {"type":"hello","serverId":"tesmp","secret":"…","protocolVersion":1,"softwareVersion":"0.1.0"}
   ```
2. The bridge replies with `{"type":"welcome","bridgeVersion":"0.1.0","protocolVersion":1}`. If it refuses, it sends `{"type":"reject","reason":"…"}` and closes the connection with code 4001.
3. No hello within 10 seconds → closed with 4001.
4. A new connection for the same `serverId` replaces the old one (closed with 4002).

## Messages

| type | direction | fields |
|---|---|---|
| `chat` | both | `id`, `tag`, `author`, `authorId`, `text`, `origin` |
| `event` | mod → bridge | `id`, `kind`, `tag`, `player`, `playerId`, `text` |
| `ping` / `pong` | both | `time` |
| `status_request` | bridge → mod | `requestId` |
| `status` | mod → bridge | `requestId`, `players[]`, `maxPlayers`, `version` |

- `text` is always plain text. Each side does its own formatting: Fluxer markdown escaping in the bridge, Minecraft colors in the mod.
- `event.kind` is one of `join`, `leave`, `death`, `advancement`, `server_start`, `server_stop`, `say`, `datapack`, `tellraw` (see `EventKind`).
- `tag` is what the bridge routes on (`links[].tags`).

## Reliability

- The mod reconnects forever with backoff of 1s, 2s, 4s … 60s.
- While disconnected, the mod queues up to 200 messages for at most 5 minutes.
- The mod pings every 20 seconds and reconnects after 60 seconds without a pong.

## Compatibility rules

- Unknown fields are ignored, so adding optional fields is safe.
- Unknown message types are logged and ignored.
- Bump `Protocol.VERSION` only for breaking changes. The bridge rejects mods with a different version.
