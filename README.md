# Fluxer ↔ Minecraft Bridge

Connects Minecraft server chat with Fluxer channels.

- Player chat in Minecraft shows up in a Fluxer channel, posted under the player's name with their skin as avatar.
- Messages in that Fluxer channel show up in game as `[Fluxer] Diego: anyone online?`.
- Optional: joins/leaves, deaths, advancements, server start/stop, `/say`, and datapack messages.
- `!players` in Fluxer lists who is online.

Built for my own friend server (Minecraft 26.3, Fabric), but it works for any number of servers and channels.

## How it works

```
 Minecraft server(s)                 Bridge (Docker container)                Fluxer
┌───────────────────┐  WebSocket   ┌──────────────────────────┐  Gateway   ┌──────────────┐
│ fluxerbridge mod   │ ───────────▶ │ auth per server          │ ◀────────▶ │ #channel  │
│ (server-side only) │ ◀─────────── │ routing table ("links")  │  HTTP API  │ #other-chat  │
└───────────────────┘              │ Fluxer bot + webhooks    │ ─────────▶ └──────────────┘
                                   └──────────────────────────┘
```

| Module | What it is |
|---|---|
| [`fabric-mod/`](fabric-mod) | Server-side Fabric mod. Decides **what** a server sends. Knows nothing about Fluxer. Players install nothing. |
| [`bridge/`](bridge) | Standalone Java app (runs in Docker). Holds the Fluxer bot token and decides **where** messages go. |
| [`protocol/`](protocol) | Message types and the reconnecting WebSocket client, shared by both. See [docs/protocol.md](docs/protocol.md). |

## Getting it running

Follow **[docs/setup.md](docs/setup.md)**. In short:

1. Create a Fluxer bot and add it to the community.
2. Build: `./gradlew build` produces `fabric-mod/build/libs/fluxerbridge-<version>.jar`.
3. Put the jar in the server's `mods/` folder and start once, which creates `config/fluxerbridge.json` with a secret.
4. Add the `fluxer-bridge` service to the Docker Compose file, with `bridge.json` and `.env`.
5. Restart and chat.

Datapack messages (such as "A villager died at x y z"): see [docs/datapacks.md](docs/datapacks.md).

## Development

You need JDK 25. See [CONTRIBUTING.md](CONTRIBUTING.md).

```sh
./gradlew build                 # build everything and run tests
./gradlew :fabric-mod:runServer # dev Minecraft server with the mod (accept the EULA in fabric-mod/run/eula.txt)
./gradlew :bridge:run --args="path/to/bridge.json"
```

| Version | |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | ≥ 0.20.1 |
| Fabric API | 0.166.x+26.3 |
| Java | 25 |
