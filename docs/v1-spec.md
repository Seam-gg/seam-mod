# Seam Companion Mod — v1 Specification

> Fabric client-side mod for Minecraft. Bridges in-game resource tracking with the Seam web application.

---

## Scope

v1 delivers two-way sync between Minecraft and Seam, scoped to:

- **Inventory logging** — automatic reading of player inventory and tagged container contents
- **Resource tracking** — live reconciliation of gathered resources against project requirements
- **Task management** — in-game task check-off for active projects
- **Container tagging** — assign chests, shulker boxes, barrels, and hoppers to specific Seam projects

Out of scope for v1: in-game planning UI, DAG/graph visualization, path generation, production rate tracking, modpack recipe support.

---

## Platform

| Property | Value |
|----------|-------|
| Mod loader | Fabric |
| Language | Kotlin (via fabric-language-kotlin) |
| Distribution | Client-only, degraded multiplayer (see Multiplayer section) |
| Minecraft versions | Target current stable (1.21.x at time of writing); version matrix TBD |
| Minimum Fabric API version | TBD based on required modules |

---

## Core Concepts

### The Field Notebook

The primary interface is a **custom in-game item** — a field notebook. It is a physical item that sits in the player's inventory or hotbar. Right-clicking opens the notebook screen.

**Design intent:** The notebook reinforces the "engineer out in the field" identity. The player isn't opening a menu — they're consulting their notebook while standing at the build site. Visual design should echo the utilitarian-technical aesthetic from the Seam design system (graph paper, dense information, monospace labels).

**Obtaining the notebook:**

- Crafted: Book + Iron Nugget + Ink Sac (trivially cheap, vanilla materials)
- Alternative: auto-granted on first world join if a Seam account is linked (TBD — craft-only is simpler for v1)

### Container Tags

Containers (chests, double chests, shulker boxes, barrels, hoppers) can be assigned to a Seam project. A tagged container's contents count toward that project's resource totals.

**One container, one project.** A container cannot be assigned to multiple projects in v1. Re-tagging overwrites the previous assignment.

**Double chests.** A joined double chest is treated as a single container. Tagging either half tags both block positions in the world data file. Untagging removes both. The floating label renders once, centered on the combined chest. Two single chests placed adjacent but not joined are separate containers with independent tags. Chunk boundary note: a double chest can straddle two chunks. Minecraft exposes a double chest as one inventory when either half is accessible — verify during implementation that the mod can read the full contents from either loaded half.

---

## Interaction Model

### Notebook — Right-click

Opens the notebook screen. This is the main UI surface.

### Container Tagging — Sneak + Right-click with Notebook

While holding the notebook, sneak + right-click on a supported container opens a small project picker overlay. Selecting a project tags the container. Sneak + right-click on an already-tagged container shows the current assignment with options to reassign or unassign.

### Container Visual — Floating Tag

Tagged containers display a floating text label above them, styled like Minecraft entity name tags. Content: project name (truncated if needed). Visible within 8–12 blocks, fading with distance.

- Render approach: custom `BlockEntityRenderer` or equivalent client-side renderer attached to the block position
- Visibility is toggleable in settings
- Color/style: `--text-primary` equivalent on a semi-transparent dark background, IBM Plex Mono if feasible (otherwise Minecraft's default font is fine for v1)

---

## Notebook Screen

Opened by right-clicking the notebook item. Full-screen custom `Screen` subclass.

### Layout

```
┌─────────────────────────────────────────────────┐
│  SEAM NOTEBOOK                        [⚙]       │
│                                                   │
│  Project: [▼ Iron Farm          ]                │
│  Status: In Progress                              │
│                                                   │
│  ─── RESOURCES ───────────────────────────────── │
│                                                   │
│  Iron Ingot       ████████░░   32 / 64           │
│    📦 Player: 12  📦 Storage: 20                 │
│  Redstone Dust    ░░░░░░░░░░    0 / 8            │
│  Oak Planks       ██████████   16 / 16  ✓        │
│                                                   │
│  ─── TASKS ───────────────────────────────────── │
│                                                   │
│  ☐  Lay out foundation                           │
│  ☐  Place hoppers                                │
│  ☑  Dig out area                                 │
│                                                   │
│  ─── CONTAINERS ──────────────────────────────── │
│                                                   │
│  Chest @ 120, 64, -430          48 items         │
│  Shulker Box @ 122, 64, -430   12 items         │
│                                                   │
│  Last synced: 12s ago                             │
└─────────────────────────────────────────────────┘
```

### Sections

**Project selector.** Dropdown listing all projects in the active world. Shows project name and status badge. Selecting a project switches the entire notebook view.

**Resources.** Mirrors the execute view from the web app. Each row shows: item name, progress bar, current count / required count. Below each row, a breakdown of where the items are: player inventory count and tagged container count. Completed resources are dimmed (consistent with web app `--text-disabled` treatment).

No manual increment buttons — the mod auto-calculates from scanned inventories. The count is the truth from the game world.

**Tasks.** Toggleable checklist. Clicking a task toggles its completion state and syncs to the Seam API. Matches the web app task list behavior.

**Containers.** List of containers tagged to this project with coordinates and total item count. Clicking a container entry could highlight its direction (stretch goal, not required for v1).

**Sync status.** Bottom of the screen: last sync timestamp, connection status indicator.

### Refresh Behavior

- On notebook open: full scan (player inventory + all tagged containers in loaded chunks) + API pull for project data
- While notebook is open: periodic refresh every 10–30 seconds (configurable in settings)
- Manual refresh button available

---

## Data Storage

### Local Data File

All mod-specific data is stored in `.minecraft/seam/` as JSON files. No Minecraft world data is modified.

```
.minecraft/seam/
├── config.json              # Global settings, auth token
├── worlds/
│   ├── {world-id}.json      # Per-world container mappings + cache
│   └── {server-hash}.json   # Per-server container mappings + cache
```

**World identification:** Singleplayer worlds identified by world directory name. Multiplayer servers identified by server address hash.

### config.json

```json
{
  "version": 1,
  "auth": {
    "token": "...",
    "username": "even",
    "linked_at": "2025-03-01T12:00:00Z"
  },
  "settings": {
    "sync_frequency": "on_open",
    "container_tags_visible": true,
    "container_tag_render_distance": 12,
    "player_inventory_mode": "off",
    "scan_ender_chest": false
  }
}
```

### Per-world file

```json
{
  "version": 1,
  "seam_world_id": "uuid",
  "active_gather_project": "uuid | null",
  "containers": {
    "120,64,-430": {
      "project_id": "uuid",
      "type": "chest",
      "tagged_at": "2025-03-01T14:00:00Z",
      "last_scanned": "2025-03-01T14:05:00Z"
    }
  },
  "project_cache": {
    "uuid": {
      "name": "Iron Farm",
      "resources": [...],
      "tasks": [...],
      "cached_at": "2025-03-01T14:05:00Z"
    }
  }
}
```

### Container Lifecycle

- **Tagging:** Sneak + right-click → entry added to world file
- **Untagging:** Via project picker overlay or settings screen → entry removed
- **Container broken:** Mod detects block change at tagged position → entry marked stale or removed (configurable: auto-remove or prompt on next notebook open)
- **Container moved:** Player breaks and replaces → old mapping stale, new placement requires re-tagging. This is acceptable for v1.

---

## Resource Tracking

The mod uses a **hybrid model**: event-based tracking for gathering and snapshot scanning for stored inventory. The two are complementary — event tracking catches the flow of items during active play, container snapshots catch the resting state of project storage.

### Gather Mode (Event-Based)

When the player has an active gather project set, the mod listens for **item pickup events**. Any item entering the player's inventory that matches a required resource for the active project is counted as gathered. This is cumulative — the count only goes up from pickup events, never down.

This handles high-throughput gathering workflows (filling 80 shulker boxes with sand in a desert) with zero friction. The player doesn't need to tag shulkers or interact with the notebook at all — they just play the game and progress tracks itself.

**What gather mode tracks:** item pickup events only. Items that leave the player's inventory (placed in chests, crafted, dropped, lost to lava) do not decrement the gather count. This is intentional — the mod is answering "how much have I collected?" not "how much do I currently have?" The latter is what container snapshots are for.

**Crafting transformations:** if a player picks up 5 iron ingots and crafts a hopper, the gather count shows 5 iron ingots gathered. The hopper appearing in a container snapshot is tracked separately. The mod doesn't understand crafting — it's a dumb sensor. Server-side reconciliation against the crafting graph is a future enhancement (see Future Enhancements).

**Offline behavior:** when the Seam API is unreachable, pickup events queue locally in the world data file as an append-only log. On reconnect, the mod flushes the queue to the API and then performs a fresh snapshot scan. This is primarily relevant for singleplayer — the web app won't be in use during offline play, so there's no conflict risk.

### Player Inventory Mode

Controls whether and how gather mode is active. Default is **off**. Configured in settings, persisted in `config.json`.

| Mode | Behavior |
|------|----------|
| **Off** | No item pickup tracking. Only tagged container snapshots contribute to resource counts. |
| **Active gather project** | Pickup events count toward the designated project only. Other projects are unaffected. |
| **All projects** | Pickup events are matched against all active projects' required resources. Noisiest option — counts increment for every project that needs the picked-up item. |

The **active gather project** is set via the notebook UI (project selector dropdown) and persisted per-world in the world data file. It survives session restarts. Changing it is an intentional action — the player is declaring "I'm gathering for this project right now."

### Container Snapshots (Scan-Based)

Tagged containers are scanned periodically or on notebook open. This captures the resting state of project storage — the iron farm output chest, the project supply room, etc.

| Source | Condition | Notes |
|--------|-----------|-------|
| Tagged containers | In loaded chunks only | Walk all tagged block positions, read block entity inventory if chunk loaded. Per-project based on container tag. |
| Ender chest | Toggleable, off by default | Contents are player-specific. Follows same project scoping rules as player inventory mode. |

### Reconciliation

The resource count pushed to the Seam API per project is: **the higher of (cumulative gathered via events) or (current snapshot total from tagged containers + player inventory if applicable).**

This handles the common case correctly: you gather 100 sand via pickup events, then place it all in a tagged chest. The snapshot shows 100 sand in the chest. The count is 100 either way — no double-counting, no loss.

Edge case: items genuinely lost (lava, despawn, used for a different purpose). The gather count stays high, the snapshot count reflects reality. The "higher of" heuristic means the count won't drop, which could be misleading. Manual correction via the notebook or web app is the escape valve. The server-side base resource expansion (future enhancement) will handle crafting transformations more intelligently.

### Unloaded Chunks

Containers in unloaded chunks cannot be scanned. The notebook UI shows: "2 containers not in range" (or similar). Last known counts from the previous scan are displayed as stale data with a visual indicator.

### Sync Payload

```json
{
  "project_id": "uuid",
  "resources": [
    {
      "item": "minecraft:iron_ingot",
      "gathered": 45,
      "snapshot": {
        "player_inventory": 5,
        "containers": 32,
        "ender_chest": 0
      },
      "effective_count": 45
    }
  ],
  "scanned_containers": 3,
  "unloaded_containers": 1,
  "timestamp": "2025-03-01T14:05:00Z"
}
```

The server receives both the cumulative gathered count and the snapshot breakdown. It stores both and applies its own reconciliation logic (which may evolve as the base resource expansion feature is built).

---

## API Integration

### Required Seam Backend Endpoints

These are new endpoints — the existing web routes are HTML-based and not suitable for mod consumption.

```
POST   /api/v1/auth/device-code         # Initiate device code flow
POST   /api/v1/auth/device-code/poll     # Poll for token
DELETE /api/v1/auth/token                # Revoke token

GET    /api/v1/worlds                    # List worlds for authenticated user
GET    /api/v1/worlds/:id/projects       # List projects with resources + tasks

POST   /api/v1/projects/:id/resources/sync   # Push resource snapshot
PUT    /api/v1/projects/:id/tasks/:id        # Toggle task completion
```

### Authentication — Device Code Flow

1. Player opens Settings → Link Account in the notebook
2. Mod calls `POST /api/v1/auth/device-code` → receives `{ code: "ABCD-1234", verification_url: "https://seam.gg/link", expires_in: 600 }`
3. Notebook screen displays: "Go to seam.gg/link and enter code: ABCD-1234"
4. Player enters code on the Seam website (on their phone or PC browser)
5. Mod polls `POST /api/v1/auth/device-code/poll` with the device code until it receives a token
6. Token stored in `config.json`

This avoids pasting tokens into config files and mirrors the familiar Microsoft/Xbox auth flow.

### Sync Direction

**Mod → Seam (push):**
- Resource snapshots (aggregated inventory counts per project)
- Task completion toggles

**Seam → Mod (pull):**
- Project list with resource requirements and current web-reported counts
- Task list with current state

### Conflict Resolution

The mod's resource snapshot is authoritative when the mod is active — it has ground truth from the game world. The web app's manual counters are a fallback for when the mod isn't running.

Rule: **last write wins**, with source tracking.

Resource counts on the Seam backend gain a `source` field: `"manual"` (web counter) or `"mod"` (inventory scan). The web UI displays a "synced from mod" indicator when the last update came from the mod. Manual web edits override mod counts (the user might be correcting something), and the next mod scan overrides manual counts (the game world is the truth).

---

## Settings Screen

Accessible from the gear icon in the notebook header. Rendered as a tabbed or scrollable screen within the notebook UI.

### Account

- Connection status: linked / not linked
- Linked username
- Link Account button (triggers device code flow)
- Unlink Account button (clears token, confirms first)

### Sync

- Sync frequency: On notebook open only / Periodic (10s, 30s, 60s)
- Auto-push resource counts: on / off (if off, manual "Sync now" button required)
- Last sync timestamp and result (success / error message)

### Display

- Container tag visibility: on / off
- Container tag render distance: slider, 4–16 blocks
- Notebook item in recipe book: on / off (for creative mode or modpack scenarios)

### Containers

- List of all tagged containers for the current world
- Each entry: block type, coordinates, assigned project name, last scanned timestamp
- Unassign action per entry (useful for containers in unloaded chunks)
- "Clear all tags" with confirmation

### Notifications

- Progress notification verbosity: Off / Completions only / All milestones (default: all milestones)

### Scanning

- Player inventory mode: Off / Active gather project / All projects (default: off)
- Active gather project selector (shown when mode is "active gather project"): dropdown of projects in the current world. Persisted per-world.
- Scan ender chest: on / off (follows same project scoping as player inventory)
- Stale container behavior: auto-remove / prompt on next open

---

## Progress Notifications

Resource progress milestones are reported as **client-side chat messages**. No custom overlay renderer — chat is native, familiar, scrollable, and free.

### Message Format

All messages are prefixed with `[Seam]` in the mod's accent color for easy visual filtering.

```
[Seam] Iron Ingot — 50% (32/64) for Iron Farm
[Seam] Redstone Dust — 75% (6/8) for Auto Crafter
[Seam] Iron Ingot — Complete ✓ for Iron Farm
[Seam] Iron Farm — All resources gathered
```

Project name is a clickable chat link that opens the notebook to that project.

### Trigger Thresholds

| Event | When | Color treatment |
|-------|------|-----------------|
| Milestone | Resource crosses 25%, 50%, 75% | Muted / default text |
| Resource complete | Resource hits 100% | Green (✓ suffix) |
| Project resources complete | All resources in a project reach 100% | Green, distinct message |

Milestones are calculated on scan completion. If a single scan pushes a resource from 20% to 60%, only the 25% and 50% messages fire (in order). If it jumps from 20% to 100%, all intermediate milestones are skipped and only the completion message fires — no spam.

### Settings

Notification verbosity: Off / Completions only (100% + all-resources) / All milestones (25/50/75/100%). Default: **all milestones**.

Added to the settings screen under a **Notifications** section.

### v2 Server Component — Broadcast

With the server-side mod, progress messages become broadcastable to all linked players on the server. A server-side config controls: off / broadcast completions only / broadcast all milestones. This lets the team see "Even finished gathering iron for Iron Farm" without requiring each player to check the web app. Individual players can still mute notifications client-side.

---

## Multiplayer Behavior

The mod is **client-only**. No server-side component in v1.

### What works in multiplayer

- Player inventory scanning (client has full access)
- Notebook UI and project display
- Task toggling and API sync
- Container tagging (stored locally)
- Floating container tags (rendered client-side)
- Progress notifications (client-side chat only — only the local player sees them)

### What's degraded in multiplayer

- Container scanning: client can only read containers it has open. The mod can scan containers the player has opened during the current session (by caching contents on container open events), but cannot proactively walk block entities in other chunks.
- Container tags are per-client — other players using the mod won't see your tags unless a server component is added later.
- Progress notifications are local only — no team-wide broadcasts without the server component.

### Future server component (v2+)

A server-side Fabric mod would enable: shared container tags across all linked players, server-side container scanning (no chunk loading dependency), push-based inventory events, and broadcast progress notifications to all linked players on the server.

---

## Project Structure

```
seam-mod/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties              # Minecraft version, Fabric versions, mod metadata
├── src/
│   └── main/
│       ├── kotlin/gg/seam/mod/
│       │   ├── SeamMod.kt                    # Client mod initializer
│       │   ├── item/
│       │   │   └── NotebookItem.kt           # Custom item definition + right-click handler
│       │   ├── screen/
│       │   │   ├── NotebookScreen.kt         # Main notebook screen
│       │   │   ├── ProjectPickerOverlay.kt   # Container tagging project picker
│       │   │   └── SettingsScreen.kt         # Settings UI
│       │   ├── data/
│       │   │   ├── SeamConfig.kt             # Config file read/write
│       │   │   ├── WorldData.kt              # Per-world container mappings + cache
│       │   │   └── Models.kt                 # API response models
│       │   ├── scanning/
│       │   │   ├── InventoryScanner.kt       # Player inventory reader
│       │   │   ├── ContainerScanner.kt       # Tagged container reader
│       │   │   └── ResourceAggregator.kt     # Combines sources into per-project snapshot
│       │   ├── network/
│       │   │   ├── SeamApiClient.kt          # HTTP client for Seam API
│       │   │   └── DeviceCodeAuth.kt         # Device code flow implementation
│       │   └── render/
│       │       └── ContainerTagRenderer.kt   # Floating label renderer
│       └── resources/
│           ├── fabric.mod.json
│           ├── seam-mod.mixins.json          # If mixins are needed
│           └── assets/seam/
│               ├── textures/item/
│               │   └── notebook.png          # Notebook item texture
│               ├── models/item/
│               │   └── notebook.json         # Item model
│               └── lang/
│                   └── en_us.json            # Translations
```

### Dependencies

| Dependency | Purpose |
|------------|---------|
| fabric-language-kotlin | Kotlin support on Fabric |
| fabric-api | Core Fabric hooks (items, rendering, events) |
| ktor-client (CIO or OkHttp) | HTTP client for Seam API calls |
| kotlinx-serialization-json | JSON serialization for config + API |

### Documentation References

The Fabric documentation at `docs.fabricmc.net` is versioned per Minecraft release and covers all areas the mod needs. Fetch the relevant doc page before implementing each component — API patterns shift between versions and LLM training data may be stale.

| Mod Component | Fabric Doc Page | Notes |
|---------------|-----------------|-------|
| Notebook item | `/develop/items/first-item`, `/develop/items/custom-item-interactions` | Custom item registration + right-click handler |
| Notebook screen | `/develop/rendering/gui/custom-screens`, `/develop/rendering/gui/custom-widgets` | `Screen` subclass, custom widget rendering |
| Floating container tags | `/develop/blocks/block-entity-renderer`, `/develop/rendering/world` | Rendering text at block positions in-world |
| Item pickup events | `/develop/events` | Fabric API event hooks for gather mode |
| Data-driven recipe | `/develop/data-generation/recipes` | JSON recipe for the notebook item |
| Keybinds (future) | `/develop/key-mappings` | If keybind alternative is added later |
| Text formatting | `/develop/text-and-translations` | Chat message formatting for progress notifications |

**Additional references:**

- **Example mod repo:** `github.com/FabricMC/fabric-example-mod` — canonical project scaffold, kept current per Minecraft version
- **Fabric API source:** `github.com/FabricMC/fabric` — when docs are unclear, the API source is the ground truth
- **Version migration guides:** `fabricmc.net/2024/05/31/121.html` and `/2024/12/02/1214.html` — breaking changes per Minecraft version

**Early spike recommended:** ktor-client running inside a Fabric mod's classloader environment. The mod runs inside Minecraft's JVM with Knot (Fabric's classloader), which is different from a standalone Ktor app. Potential issues: conflicting Netty versions (Minecraft bundles its own), classloader isolation, thread pool behavior. Test basic HTTP calls early before building the full API client.

---

## Resolved Questions

- **World-to-Seam-world mapping:** Manual linking. Player picks from their Seam worlds in the notebook settings. Stored in the per-world data file as `seam_world_id`. ✓
- **Notebook item namespace:** `seam:notebook`. No known conflicts. ✓
- **Recipe registration:** Data-driven JSON. Standard Fabric approach, allows modpack authors to tweak. ✓
- **Multiplayer container caching:** Time-based staleness. Cached container contents from open events are trusted for 5 minutes, then marked stale. Stale entries show a "last opened" timestamp in the notebook. ✓
- **Ender chest handling:** Ender chest is an extension of player inventory. Follows the same player inventory mode rules (off / active gather project / all projects). Cannot be tagged to a project independently. No separate config needed. ✓
- **Offline behavior:** Gather mode pickup events queue locally in the world data file (append-only). On reconnect, the mod flushes the queue to the API and then does a fresh snapshot scan. Primarily relevant for singleplayer — the web app won't be touched during offline play, so there's no conflict risk. ✓
- **Double chests:** Treated as a single container. Tagging one half tags both block positions. Untagging removes both. Floating label renders on the combined chest, not on each half separately. Only applies when the two chests are actually joined (forming a double chest), not when two single chests happen to be adjacent. Chunk boundary handling: a double chest can straddle a chunk boundary. Since Minecraft exposes a double chest as one inventory when either half is loaded, the mod should get full contents from either half — verify during implementation. If only one half's chunk is loaded, treat as partially scannable and flag accordingly. ✓

## Open Questions

- **Rate limiting:** How frequently can the mod hit the Seam API before it becomes a problem? Relevant for periodic sync on busy servers with many linked players. Needs load estimation once the API is built.
- **Gather event queue size:** Should the local offline queue have a cap, or grow unbounded? Long singleplayer sessions could accumulate a large queue. Likely fine in practice (it's just item IDs + counts), but worth a sanity check.

---

## Relationship to Web App

The mod is a **companion**, not a replacement. The web app remains the full-featured planning surface (plan view, production paths, roadmap, idea hub). The mod is the execute-view-in-the-game — it covers the "I'm playing Minecraft and want to track my progress" workflow without alt-tabbing or picking up a phone.

Resource counts on the web update in near-real-time when the mod is syncing. Manual web counter edits are still possible and respected. The `source` field on resource counts lets both surfaces coexist without confusion.

---

## Future Enhancements

Notes on improvements that build on v1 but are out of scope for the initial release.

### Base Resource Expansion (server-side, affects mod + web)

The production path already computes the full crafting tree from project-level requirements down to base/raw materials. A future improvement surfaces this in the execute view: alongside the project-level resource list (8 hoppers, 4 pistons), show an **expanded base resource list** (40 iron ingots, 64 cobblestone, 16 redstone, 24 planks) — the actual items the player needs to gather.

This changes how the server reconciles mod data. The mod reports raw pickup events and container snapshots. The server, knowing the crafting graph, can infer that 40 iron ingots gathered + 8 hoppers appearing in a snapshot = iron ingot requirement fully satisfied (because it knows hoppers consume iron). The mod stays a dumb sensor; the server does the accounting.

This is a production path builder enhancement, not a mod feature. The mod inherits it for free via the API — the notebook resource list just switches between "project resources" and "base resources to gather" views, both served by the backend.

### In-Game Planning UI (v2+)

Full planning interface inside Minecraft: project creation, resource definition, path visualization. Likely requires a more sophisticated screen renderer or integration with a client-side UI framework. DAG visualization in-game is a significant rendering challenge. Deferred until the execute-view mod loop is validated with real users.

### Server-Side Mod Component (v2+)

Enables: shared container tags, server-side container scanning, broadcast progress notifications, and potentially real-time inventory events pushed to all linked clients. See Multiplayer Behavior section for details.
