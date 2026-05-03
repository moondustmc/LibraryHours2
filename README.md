# LibraryHours

LibraryHours is a Spigot/Paper plugin for defining quiet library regions that reward players while they spend time inside them. It also isolates chat around those regions: outside chat cannot enter, inside chat cannot leave, and players inside the same library region get a local library chat.

## Features

- Define one or more cuboid library regions.
- Give passive EXP and coin rewards to players inside any library region.
- Store player coin balances in `playerdata.yml`.
- Block normal and private chat across the library boundary.
- Provide local chat for players sharing the same library region.
- Integrate with VentureChat when it is installed, including receive-side blocking for VentureChat channel listeners.
- Supports standard `&` colors and hex colors such as `#cca66e` in language messages.

## Requirements

- Java 21 to compile the plugin.
- Spigot/Paper API `1.21`.
- Optional: VentureChat. The plugin declares VentureChat as a soft dependency and will hook it when present.

## Building

On Windows:

```powershell
.\gradlew.bat build
```

If Gradle is using an older Java runtime, point `JAVA_HOME` at a Java 21 JDK first:

```powershell
$env:JAVA_HOME='C:\Path\To\Java21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build
```

The built jar is written to:

```text
build/libs/LibraryHours-1.0.jar
```

## Installation

1. Stop the server.
2. Copy `LibraryHours-1.0.jar` into the server `plugins` folder.
3. Start the server.
4. Configure regions in game with `/library pos1`, `/library pos2`, and `/library save <name>`.

Use a full server restart after updating the jar. `/library reload` reloads config and language files only; it does not reload Java code changes.

## Commands

| Command | Description |
| --- | --- |
| `/library pos1` | Set the first corner of a region selection. |
| `/library pos2` | Set the second corner of a region selection. |
| `/library save <name>` | Save or update a selected library region. |
| `/library remove <name>` | Remove a saved library region. |
| `/library list` | List configured regions. |
| `/library info [name]` | Show region and reward information. |
| `/library reload` | Reload `config.yml`, `lang.yml`, regions, and player coin data. |
| `/library balance` | Show your library coin balance. |

## Permissions

| Permission | Default | Description |
| --- | --- | --- |
| `libraryhours.admin` | `op` | Allows configuring and removing library regions and reloading the plugin config. |

## Configuration

Default `config.yml`:

```yml
library:
  regions: {}

rewards:
  interval-seconds: 60
  exp: 5
  coins: 10
```

Regions are saved under `library.regions` after using `/library save <name>`.

Reward values:

- `rewards.interval-seconds`: how often rewards are given.
- `rewards.exp`: EXP amount per interval.
- `rewards.coins`: library coin amount per interval.

## Chat Behavior

LibraryHours enforces a chat boundary around each saved library region:

- Players outside a library region cannot send normal chat or private messages to players inside.
- Players inside a library region cannot send normal chat or private messages outside.
- Players inside the same library region can use local library chat.
- If regions overlap, local chat is delivered to players sharing at least one same library region.
- VentureChat listeners are temporarily suspended while a player is inside a library region so global/channel messages do not leak in.

The local chat format is configured in `lang.yml`:

```yml
messages:
  local-chat: "#cca66e[Library] #99683d%player%#332920: #cca66e%message%"
```

Available placeholders:

- `%player%`: sender display name.
- `%message%`: chat message.
- `%regions%`: comma-separated shared region names.

If an existing server already has a `lang.yml` without `messages.local-chat`, the plugin uses a built-in fallback format.

## Data Files

- `config.yml`: saved regions and reward settings.
- `lang.yml`: player-facing messages and formatting.
- `playerdata.yml`: player coin balances.

## Notes

- Region names may contain letters, numbers, hyphens, and underscores.
- Regions must have both selection corners in the same world.
- Coin balances are saved automatically and during plugin shutdown.
