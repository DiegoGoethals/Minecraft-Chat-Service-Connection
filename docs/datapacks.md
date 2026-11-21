# Sending datapack messages to Fluxer

There are three ways a datapack message can reach Fluxer. Pick one per message.

## 1. `/fluxerbridge send` (recommended)

Add one line next to the datapack's existing `tellraw`. Use the **same text component** as the tellraw:

```mcfunction
# existing line, unchanged: players still see it in game
tellraw @a ["A villager died at ",{"nbt":"Pos[0]","entity":"@s"}," ",{"nbt":"Pos[1]","entity":"@s"}," ",{"nbt":"Pos[2]","entity":"@s"}]

# new line: the same message goes to Fluxer
fluxerbridge send ["A villager died at ",{"nbt":"Pos[0]","entity":"@s"}," ",{"nbt":"Pos[1]","entity":"@s"}," ",{"nbt":"Pos[2]","entity":"@s"}]
```

- Selectors, scores and NBT are resolved just like tellraw does it, using the entity running the function (`@s`).
- It is sent with routing tag `datapack`. Use `fluxerbridge sendto <tag> <component>` to pick another tag, for example `fluxerbridge sendto villagers [...]`, then route that tag to its own channel in `bridge.json`.
- Needs permission level 2. Datapack functions have that by default (`function-permission-level` in `server.properties`).
- Turn it off with `send.datapack: false` in `config/fluxerbridge.json`.

Nothing private leaks: only messages you explicitly send this way go to Fluxer.

## 2. `say`

If the datapack uses `say A villager died…`, it's forwarded automatically (when `send.say` is true), shown as `[Server] A villager died…`.

## 3. Capture `tellraw` without changing the datapack

If you can't or don't want to edit the datapack, turn on the tellraw hook in `config/fluxerbridge.json`:

```json
"tellraw": {
  "enabled": true,
  "onlyEveryone": true,
  "patterns": ["(?i)villager .*died"],
  "tag": "tellraw"
}
```

- `onlyEveryone`: only `tellraw @a …` (selector starting with `@a`), never messages sent to one player.
- `patterns`: Java regular expressions. When the list isn't empty, only matching messages are sent. **Use this**, otherwise every `tellraw @a` from every datapack goes to Fluxer.
- Run `/fluxerbridge reload` after editing.

This hooks into Minecraft's tellraw command code. If a future Minecraft version changes that code, the hook silently stops working (the server won't crash). Options 1 and 2 don't have that risk.
