# Rouge

An in-game **AI redstone assistant** for Minecraft, accessed entirely through
chat. Type `/rouge` to open a session, talk to a redstone-focused AI in chat, and
type `/rouge` again to close it.

- **Client-side** Fabric mod (works in singleplayer and on any server — no
  server-side install).
- Your messages are intercepted while a session is open, sent to
  [OpenRouter](https://openrouter.ai), and the reply is printed back to **your own
  chat** as a purple `[Rouge]` line. The original message is never sent to public
  chat.
- Conversation memory lasts for the session and is **cleared when you close it**.

## Requirements

- **Minecraft 1.20.1** with **Fabric Loader** + **Fabric API**
- **JDK 21** to run the build. Fabric Loom 1.16 requires Java 21, even though the
  mod itself compiles to **Java 17** bytecode (so the built jar runs on a normal
  1.20.1 client). The Gradle wrapper (`./gradlew`) handles the Gradle version
  itself — you only need to supply a JDK.
- An **OpenRouter API key** — sign in at <https://openrouter.ai>, open **Keys**,
  and create one. Free models work without paid credits.

## Setup

### 1. Install JDK 21

On this machine, Homebrew can't install a JDK (the `temurin` cask needs `sudo`
and the `openjdk` formula needs full Xcode), so use a prebuilt **Temurin** tarball
— no admin rights required:

```sh
mkdir -p "$HOME/.jdks"
curl -fL -o /tmp/temurin21.tar.gz \
  "https://api.adoptium.net/v3/binary/latest/21/ga/mac/x64/jdk/hotspot/normal/eclipse"
tar -xzf /tmp/temurin21.tar.gz -C "$HOME/.jdks"
```

This extracts to `~/.jdks/jdk-21.0.x+y/Contents/Home`. The build JDK is pinned in
`gradle.properties` via `org.gradle.java.home`, so **no `JAVA_HOME` export is
needed**. If your JDK ends up at a different path (e.g. a newer patch version),
update that one line in `gradle.properties` to match.

### 2. Add your OpenRouter API key

Put the key in a `.env` file at the project root. This file is **gitignored —
never commit it**:

```sh
echo 'OPENROUTER_API_KEY=sk-or-...your-key...' > .env
```

How the key is loaded:

- **Development (`./gradlew runClient`):** `build.gradle` reads `.env` fresh on
  every build and injects the key into the dev client. (Reading from `.env` —
  rather than the shell environment — avoids a stale Gradle daemon serving an old
  value.) If `.env` is absent, it falls back to the `OPENROUTER_API_KEY`
  environment variable.
- **Production install:** the mod reads the `OPENROUTER_API_KEY` environment
  variable directly, so set it in the launcher's environment.

## Run (development)

```sh
./gradlew runClient
```

The first launch downloads Minecraft assets and is slow; later launches are fast.
Then in-game:

1. Enter a **singleplayer world** (the AI call needs you in a world).
2. Press `T`, type `/rouge` → `[Rouge] Session opened.`
3. Ask a redstone question, e.g. `how do I make a 2-tick repeater clock?` — the
   reply prints in purple, visible only to you.
4. Type `/rouge` again to close the session.

### Troubleshooting

- **`[Rouge] OpenRouter rejected the API key` / "No auth credentials found"** —
  the key isn't reaching the game. Confirm `.env` contains a valid
  `OPENROUTER_API_KEY=...` line, then force a clean run: `./gradlew --stop`
  followed by `./gradlew runClient`.
- **`429` rate limit** — free models are rate-limited; wait a few seconds, or
  switch to another model (see Configuration).
- **Startup log** should show `Rouge initialized (model: ...)` with no
  missing-token warning.

## Configuration

- **Model:** the default is `openai/gpt-oss-20b:free`. Change the `model` field in
  `src/client/java/dev/dhanika/rouge/ai/OpenRouterConfig.java` — including to a
  paid model — with no other code changes. Free model ids change; verify current
  ones at <https://openrouter.ai/models>.
- **System prompt:** edit `src/main/resources/rouge/system_prompt.txt` (the
  redstone-tutor persona). It's loaded from resources, so no code change needed.

## Architecture

| Package | Responsibility |
| --- | --- |
| `ai/` | Reusable OpenRouter client (no Minecraft imports) |
| `prompt/` | Loads the swappable system prompt |
| `session/` | Single source of truth: open/closed state + history |
| `chat/` | Chat interception (`ALLOW_CHAT`) + local chat output |
| `command/` | `/rouge` command registration |
| `RougeClient` | Entry point — wires everything together |

The `ai/` and `command/` packages are structured so future features (e.g. a
`/rougebuild` command that generates schematics) plug in without touching the
existing modules.
