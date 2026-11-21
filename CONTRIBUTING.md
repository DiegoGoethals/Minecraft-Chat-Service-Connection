# Contributing

## Setup

- **JDK 25** (for example Temurin 25). Gradle runs on it: set `JAVA_HOME` to it, or select it in your editor.
- Any editor works. For VS Code or Cursor, install the "Extension Pack for Java" and "Gradle for Java" extensions.
- The Gradle wrapper downloads everything else, including Minecraft for the mod module.

```sh
./gradlew build                      # compile everything and run all tests
./gradlew :bridge:test               # bridge tests only (fast, no Minecraft)
./gradlew :fabric-mod:runServer      # local Minecraft server with the mod
./gradlew :bridge:run --args="bridge.json"
```

For `runServer`, set `eula=true` in `fabric-mod/run/eula.txt` after the first run (that's accepting Mojang's EULA). To test end to end on your own machine:
- Set `bridgeUrl` to `ws://127.0.0.1:8765` in `fabric-mod/run/config/fluxerbridge.json`.
- Run the bridge with a test bot and a test channel.

## Layout

```
protocol/    shared message types + BridgeClient (used by the mod; tested from the bridge)
bridge/      Main, Router (all routing rules), MinecraftHub (WebSocket server),
             FluxerOutbox (posting queue), fluxer/ (Gateway + HTTP API client)
fabric-mod/  FluxerBridgeMod (entrypoint), MinecraftEvents, BridgeCommand,
             TellrawCapture + mixin/TellRawCommandMixin
docs/        setup guide, protocol, datapacks, example configs
```

## Rules of thumb

- **The mod never talks to Fluxer**, and the bridge never needs Minecraft. Keep it that way.
- Touch the game only on the server thread (`server.execute(...)`). Network callbacks run on other threads.
- Minecraft 26.x is not obfuscated and uses Mojang's names (`ServerPlayer`, `Component`…). Yarn names from older tutorials (`ServerPlayerEntity`, `Text`) don't exist here.
- Routing behaviour belongs in `Router` and needs a test in `RouterTest`.
- Never log tokens, secrets or webhook URLs.

## Updating Minecraft

1. Check https://fabricmc.net/develop for the new `minecraft_version`, `loader_version`, `loom_version` and `fabric_api_version`, and update `gradle.properties`.
2. Update `"minecraft"` in `fabric-mod/src/main/resources/fabric.mod.json`.
3. Run `./gradlew build`, then `runServer` and test in game, including `tellraw` capture if you use it. The mixin targets a lambda by name and silently does nothing if that name changes.
4. The bridge doesn't care about the Minecraft version.
