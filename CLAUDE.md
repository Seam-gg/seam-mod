# seam-mod — Claude context

**Fabric** Minecraft mod (**Seam Notebook**), part of the Seam workspace. Bridges in-game
resource tracking with the Seam webapp (`mc-org`, at `app.seam.gg`).

Client-only in shipped jars today; **a server half lands in the same jar** — see § Client and
server.

## Hard facts

- **Loader:** Fabric · **Minecraft:** `1.21.11` · **Language:** Kotlin via fabric-language-kotlin.
- **Names:** display name **Seam Notebook**, mod id **`seam_notebook`**, Modrinth slug
  **`seam-notebook`**. Not plain `seam` — an unrelated horror mod already owns that Modrinth slug,
  and a shared mod id is a hard load failure for anyone running both. The Linear *project* is still
  called "Seam Companion Mod"; that's internal, like `mc-org` itself.
- **Client-only today, client + server next.** Shipped jars are `environment: "client"` with a single
  `client` entrypoint (`gg.seam.mod.SeamClient`). **MCO-534** flips `environment` to `"*"` and adds a
  `main` entrypoint (`gg.seam.mod.SeamServer`) — **one jar, two halves**, released as `0.3.0` (a minor:
  the Modrinth metadata changes from client-only to client *and* server).
  *(This said a server component was v2 and would be prototyped "**as a script** … not a second mod
  artifact" until 2026-09-08. "Not a second artifact" survives — the script does not. The Shared
  Storage project needs the server half in the shipped jar, so a server operator installs one thing.)*
- **Build:** Loom 1.17 + Gradle 9.5, runs on **JDK 21+** (Gradle ≥9.1 supports Java 25, so the local
  JDK 25 is fine). The mod still *targets* Java 21 (MC 1.21.11's runtime) via `jvmToolchain(21)` —
  that's independent of the JDK running Gradle. Verified coordinates in `gradle.properties`;
  re-verify at https://fabricmc.net/develop on MC bumps.
- **Mappings:** Yarn.

## Read before coding

**`docs/fabric-1.21.11-reference.md`** is the source of truth for the 1.21.x API. It carries a
"version-churn hazard map" of ~10 recent API breaks this mod passes through — the 1.21.9 renderer
rewrite (WorldRenderEvents), 1.21.5 ClickEvent→records, 1.21.9 input KeyInput wrappers, 1.21.6
drawTexture RenderPipeline arg, 1.21.2 ActionResult merge, etc. Do not copy Fabric-docs-site or
old-tutorial code without checking it against that map — the live docs site renders a newer
snapshot / Mojmap model that does not match 1.21.11 Yarn.

## Client and server — one jar, two halves

Settled by **MCO-534**; the sweep it carries is **MCO-260**. Not built yet — this is the shape to
build to, not a description of the tree.

- **Packaging.** `environment: "*"`, a `main` entrypoint `gg.seam.mod.SeamServer`, `client` entrypoint
  untouched. The mod registers **no blocks, items or packets**, so a client without it can join a
  server with it and vice versa. There is no compatibility matrix to maintain, deliberately: **the two
  halves never speak to each other — they meet in mc-org.**
- **`gg.seam.mod.storage` must not reference `MinecraftClient`.** Anywhere. It depends only on
  `net.minecraft.server.*` and the existing API client. A stray client reference will not class-load on
  a dedicated server — the one mistake here that fails loudly and late, so it is enforced by the
  dedicated-server load test rather than by eye.
- **Singleplayer runs the same code.** `main` also fires in singleplayer's integrated server, which is
  what makes SP and MP one code path instead of two implementations. Exercise the server half in SP
  against a local webapp before the jar goes near a real server.
- **Config is `config/seam-notebook-server.json`**, read once at server start (`api_base_url`,
  `seam_world_id`, `token`, `sweep_seconds`, `reads_per_tick`). Absent or tokenless → **one INFO line
  and do nothing.** A server that installs the jar without configuring it is a supported state, not an
  error, and must not warn every tick. A malformed file is a logged error with defaults, not a crash.
- **The reporter authenticates as itself**, with a world-scoped least-privilege **reporter token**
  (MCO-531) — not a player's device-code token. It lives in a plaintext config file on someone else's
  machine; scope it accordingly.

## Design decisions already made

- **Notebook opener = keybind** (default `N`), not a custom item: a client-only item is invisible to
  the multiplayer server we run. The crafted `seam:notebook` item comes later.
- **Container tags live in the webapp** (MCO-530), not in a client-local per-world file: a client-local
  tag is invisible to the thing that reads the chest, and webapp-side is also what makes tags *shared*.
- **Containers are read by the server half, not approximated from the client.** The **sweep** (MCO-260)
  reads tagged containers in loaded chunks, on the server thread. A container in an unloaded chunk
  cannot have changed — nobody was there to change it — so its last reading is *correct* data rather
  than stale data, and loaded-chunks-only is the right answer, not an approximation of one. (That
  reasoning is specific to containers; terrain in an unloaded chunk was never read in the first place,
  which is why MCO-526 still needs the chunk-storage path.)
  *(Was "container snapshots are singleplayer-first", with **gather mode** — item-pickup tracking via a
  `ClientPlayNetworkHandler.onItemPickupAnimation` mixin — as the reliable multiplayer path, until
  2026-09-08. Gather mode is **cancelled** as a counting mechanism (MCO-262): the count is what is in
  tagged containers, and nothing else.)*
- **HTTP = JDK `java.net.http.HttpClient`** (no ktor). **JSON = kotlinx-serialization** (provided by FLK
  at runtime — `compileOnly`, never bundle). Marshal results back to the client thread with
  `MinecraftClient.getInstance().execute { }` — on the **client half only**. Server-half code has no
  `MinecraftClient`; marshal onto the server thread with `MinecraftServer.execute { }` instead.

## Backend (mc-org, not here)

The sync backend **has landed**: **MCO-235** (read-only JSON API) + **MCO-236** (device-code auth)
shipped in mc-org as `472ce89`. The contract lives in
`mc-org/webapp/mc-web/src/main/kotlin/app/mcorg/api/ApiDtos.kt` — snake_case, `/api/v1`, bearer
token. `gg.seam.mod.api.ApiModels` is the client half of that same contract; **change the two
together.** Endpoints: `POST /auth/device-code`, `POST /auth/device-code/poll`,
`DELETE /auth/token`, `GET /worlds`, `GET /worlds/{id}/projects`,
`POST /projects/{id}/resources/sync`, `PUT /projects/{id}/tasks/{id}`.

The **Shared Storage** project adds to that contract from the mc-org side — container tags
(MCO-530), container contents and the measurement rollup (MCO-532), reporter tokens (MCO-531) and the
gathering plan's activity list (MCO-533). Same rule: the wire models change together with
`ApiDtos.kt`, in two commits in two repos.

Point the mod at a local webapp with `-Dseam.apiBaseUrl=http://localhost:8080` (or
`SEAM_API_BASE_URL`); default is `https://app.seam.gg`. See `gg.seam.mod.api.SeamApi`.

## Tests

`./gradlew test` — JUnit 5 over the Minecraft-free half (wire models, `SeamApiClient` against a
loopback `com.sun.net.httpserver`, the device-code poll policy). Anything touching `MinecraftClient`
can't be unit-tested here; that's `runClient` territory.

## Versioning & release

- Version string is `<semver>+<minecraft_version>` (`0.2.0+1.21.11`). **Only `modVersion` in
  `gradle.properties` is hand-edited** — `build.gradle.kts` appends `minecraft_version`. Read the
  composed value with `./gradlew -q printVersion`; never hardcode it anywhere.
- A release is an **annotated** tag `v<version>`, and **the tag message is the changelog** — it is
  published verbatim to the Modrinth version page. Player-facing prose only; no Linear IDs or class
  names. Use the **`/release` skill** (`.claude/skills/release/`) rather than tagging by hand.
- CI (`.github/workflows/ci.yml`) runs on every push to `main` and every PR.
  `release.yml` fires on `v*` tags, asserts tag == composed version, and gates the Modrinth upload
  behind the `modrinth` GitHub Environment's manual approval. Nothing publishes automatically.
- Pre-1.0 → Modrinth **beta** channel. `1.0.0` waits until a shipped jar must survive an mc-org
  deploy; today the mod and `ApiDtos.kt` still change together.

## Workspace rules

Commits are independent per repo. The `mc-org` worktree-first / DB-isolation rules do **not** apply
here — normal branches. Issues live in the Linear **Mcorg** team, across two projects: **Seam
Companion Mod** (the mod's own roadmap) and **Shared Storage** (the tagged-container count, which
spans mc-org and both halves of this mod). Do not create GitHub issues.
