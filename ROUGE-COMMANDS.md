# Rouge — Command Reference

All commands are client-side and typed into Minecraft chat (`T` then type). Output prints
to **your own** chat as a purple `[Rouge]` line — nothing goes to public chat.

Rouge has two independent modes:

- **Hologram chat teacher** (default) — ask Rouge to build something; it projects a
  step-by-step ghost build in front of you.
- **Canvas mode** (toggle) — sketch a circuit in the web app; Rouge's vision AI compiles
  it into a Litematica overlay you practice against.

---

## Core / session

| Command | What it does |
| --- | --- |
| `/rouge` | Toggle the chat session on/off. Open it, ask redstone questions, ask it to build something. |

## Hologram building (chat teacher)

These drive an active step-by-step build. You can also just **talk** — saying "yes", "no",
and "next" in chat does the same thing, so these commands are optional shortcuts.

| Command | What it does |
| --- | --- |
| `/rouge next` | Advance to the next build step (same as saying "next"). |
| `/rouge step` | Re-show the current step's hologram. |
| `/rouge stop` | Cancel the active build and clear the hologram. |

## Model selection

| Command | What it does |
| --- | --- |
| `/rouge model` | Show the current chat model. |
| `/rouge model <id>` | Switch the chat model (e.g. `/rouge model openai/gpt-oss-20b:free`). |

## Canvas mode (sketch → overlay)

| Command | What it does |
| --- | --- |
| `/rouge canvas` | Show whether canvas mode is on or off. |
| `/rouge canvas on` | Start the localhost bridge (port **25599**) so the web canvas can send sketches. |
| `/rouge canvas off` | Stop the bridge. |

Once canvas mode is on, open `canvas/index.html`, sketch a circuit, and hit
**Build in Minecraft →**. Then use the lesson commands below.

## Lessons (canvas mode)

| Command | What it does |
| --- | --- |
| `/rouge load` | Load the bundled sample lesson + write its overlay (works without the AI or browser). |
| `/rouge solution` | Place the full solution in the world (the answer key — singleplayer only). |
| `/rouge check` | Report your progress vs. the solution (local diff, no API call). |
| `/rouge level` | Show usage for the level command. |
| `/rouge level <basic\|easy\|medium\|hard>` | Set difficulty — hides more of the overlay so you fill in the gaps yourself. |

While you build in canvas mode, Rouge quietly points out wrong placements automatically
(computed locally, so it never spends API calls or rate-limits you).

---

## Conversational shortcuts (no slash needed)

While a `/rouge` session is open, plain chat drives the flow:

| You say | Effect |
| --- | --- |
| (a request, e.g. "teach me a T flip-flop") | Rouge proposes a build and starts the hologram. |
| `yes` / `next` | Confirm / advance to the next step. |
| `no` / `stop` | Decline / cancel the current build. |
| (any other question) | Routed to the AI — you can ask questions mid-build. |
