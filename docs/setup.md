# Setup guide

This guide gets TESMP's chat into a Fluxer channel. The Minecraft server runs in Docker with `itzg/minecraft-server`, and the bridge runs as a second container next to it.

## 1. Create the Fluxer bot

1. Create a bot application in Fluxer and **copy the token straight away**. Fluxer shows it only once. The token looks like `<application id>.<secret>`.
2. Add the bot to your community. This needs Manage Server or Administrator.
3. In the channel you want to bridge (for example `#tesmp-chat`), give the bot these permissions:
   - View Channel
   - Send Messages
   - Read Message History
   - **Manage Webhooks.** Needed for the player-name-and-skin style. Without it the bridge falls back to normal bot messages and logs a warning.
4. Copy the **channel id**. You'll need it in step 4.

## 2. Build the mod and bridge

You need JDK 25 to build.

```sh
./gradlew build
```

- Mod: `fabric-mod/build/libs/fluxerbridge-0.1.0.jar`
- Bridge: built by Docker from this repository (step 4), so you don't need to build it here.

GitHub Actions also builds both on every push (see the workflow artifacts).

## 3. Install the mod on the Minecraft server

1. Copy `fluxerbridge-0.1.0.jar` into the server's mods folder. With `itzg/minecraft-server` that's `<your data volume>/mods/`. Fabric API must be installed too, which TESMP already has.
2. Restart the server once. The mod creates `config/fluxerbridge.json` with a random `secret`:

   ```json
   {
     "bridgeUrl": "ws://fluxer-bridge:8765",
     "serverId": "tesmp",
     "secret": "6b49…",
     "send":    { "chat": true, "say": true, "datapack": true, "serverStatus": true,
                  "joins": false, "deaths": false, "advancements": false,
                  "serverStartedMessage": "The server is online",
                  "serverStoppingMessage": "The server is shutting down",
                  "sayFormat": "[{author}] {text}", "emoteFormat": "* {author} {text}" },
     "receive": { "enabled": true, "format": "&9[{origin}] &f{author}&7: &r{text}", "maxLength": 256 },
     "tellraw": { "enabled": false, "onlyEveryone": true, "patterns": [], "tag": "tellraw" }
   }
   ```

   - `bridgeUrl`: `ws://<host>:<port>` of the bridge. `fluxer-bridge` is the Docker service name from step 4 and `8765` is `listen.port` in `bridge.json`. Nothing about this is fixed: if your service or container has another name, or the bridge runs on another machine or port, put that here.
   - `secret`: copy it into `.env` as `TESMP_SECRET` (step 4).
   - `send.*`: what this server forwards. Joins, deaths and advancements are off by default.
   - `send.serverStartedMessage` / `serverStoppingMessage` / `sayFormat` / `emoteFormat`: the texts sent for those events.
   - `receive.format`: how Fluxer messages look in game. `&` color codes work here.

   Options added in a newer version of the mod aren't written into an existing file. Any option you leave out uses the default shown above, so copy in the ones you want to change.

   Players don't need the mod. It's marked server-only.

## 4. Run the bridge container

Next to your `docker-compose.yml`:

1. Put a checkout of this repository in `./fluxer-minecraft-bridge`.
2. Create `./fluxer-bridge/bridge.json`. Start from [`bridge.example.json`](bridge.example.json) and set `links[0].channelId` to your channel id.
3. Create `.env` (see [`.env.example`](../.env.example)):

   ```sh
   FLUXER_TOKEN=<bot token>
   TESMP_SECRET=<secret from config/fluxerbridge.json>
   ```

4. Add the service to your compose file (full example: [`docker-compose.example.yml`](docker-compose.example.yml)):

   ```yaml
     fluxer-bridge:
       build: ./fluxer-minecraft-bridge
       restart: unless-stopped
       env_file: .env
       volumes:
         - ./fluxer-bridge/bridge.json:/config/bridge.json:ro
   ```

   Don't add `ports:`. The mod reaches the bridge over the internal Docker network.

   **The Minecraft container and the bridge must share a Docker network.** Two services in the same compose file already do. If the Minecraft container is started some other way, create a network (`docker network create mc`) and attach both containers to it.

5. Start it and check the log:

   ```sh
   docker compose up -d --build fluxer-bridge
   docker compose logs -f fluxer-bridge
   ```

   You should see `Bot account: …`, `Connected to Fluxer as …`, and once the Minecraft server is up, `Server "tesmp" authenticated`.

## 5. Test

- In game, run `/fluxerbridge status`. It should say `connected`.
- Say something in game. It should appear in the Fluxer channel under your name and skin.
- Write in the Fluxer channel. It should appear in game as `[Fluxer] <name>: …`.
- Type `!players` in Fluxer.

## Changing things later

| Want to… | Change |
|---|---|
| Forward joins/deaths/advancements | `send.joins` / `send.deaths` / `send.advancements` in `config/fluxerbridge.json`, then `/fluxerbridge reload` |
| Route deaths to a separate channel | Add a second link with the same `server`, another `channelId` and `"tags": ["deaths"]`, and set the first link's `tags` to the rest (e.g. `["chat","say","status"]`) |
| Add another Minecraft server | Install the mod there with a different `serverId`, then add it under `servers` and `links` in `bridge.json` |
| Plain bot messages instead of webhooks | `"style": "bot"` on the link |
| Use a webhook you made yourself | Set the link's `webhookName` to that webhook's name. The bridge reuses an existing webhook with that name (preferring one it created) and only creates one when none exists. |
| One-way only | `toFluxer` / `toMinecraft` on the link |
| Different bridge host, container name or port | `bridgeUrl` in `config/fluxerbridge.json`, plus `listen.port` in `bridge.json` for the port |
| Server start/stop texts, `/say` and `/me` format | `send.serverStartedMessage`, `send.serverStoppingMessage`, `send.sayFormat`, `send.emoteFormat` in `config/fluxerbridge.json` |
| Rename `!players` or change its replies | `commands.prefix`, `commands.players`, `commands.playersOnline`, `commands.playersNone`, `commands.serverOffline` in `bridge.json` |
| Offline notice text or how often it's posted | `offlineNotice` (empty disables it) and `offlineNoticeCooldownMinutes` on the link |
| Config file somewhere else | Pass its path as the first argument, or set `BRIDGE_CONFIG` |

Routing tags sent by the mod: `chat`, `say`, `status` (server start/stop), `joins`, `deaths`, `advancements`, `datapack`, `tellraw`, plus any custom tag used with `/fluxerbridge sendto`.

## Troubleshooting

| Symptom | Cause |
|---|---|
| Bridge exits with "Fluxer refused the bot token" | Wrong `FLUXER_TOKEN`. Regenerate the token in Fluxer if needed. |
| Bridge log: "Refused connection … wrong secret" | `servers.<id>.secret` in `bridge.json` (here `TESMP_SECRET`) doesn't match `secret` in the mod config |
| Bridge log: "Refused connection … is not in the bridge config" | The mod's `serverId` has no entry under `servers` in `bridge.json` |
| Mod log: "Could not connect to bridge at …: UnresolvedAddress / ConnectException" | The containers aren't on the same network, or `bridgeUrl` doesn't match the bridge's service/container name and `listen.port` |
| Bridge log: "Can't listen on …" | Something else uses that port. Change `listen.port` in `bridge.json` and the port in `bridgeUrl` |
| Messages appear as the bot, not the player | The bot is missing Manage Webhooks in that channel. Grant it and restart the bridge. |
| Fluxer messages don't reach Minecraft | The bot needs View Channel and Read Message History. Check `channelId`. |
