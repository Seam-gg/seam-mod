# seam-mod — Claude context

Client-side **Fabric** Minecraft mod (**Seam Notebook**), part of the Seam workspace.
Bridges in-game resource tracking with the Seam webapp (`mc-org`, at `app.seam.gg`).

## Hard facts

- **Loader:** Fabric · **Minecraft:** `1.21.11` · **Language:** Kotlin via fabric-language-kotlin.
- **Names:** display name **Seam Notebook**, mod id **`seam_notebook`**, Modrinth slug
  **`seam-notebook`**. Not plain `seam` — an unrelated horror mod already owns that Modrinth slug,
  and a shared mod id is a hard load failure for anyone running both. The Linear *project* is still
  called "Seam Companion Mod"; that's internal, like `mc-org` itself.
- **Client-only** in v1. `environment: "client"`, single `client` entrypoint (`gg.seam.mod.SeamClient`).
  A server component is v2 and will be prototyped **as a script** against our own Fabric server, not
  a second mod artifact.
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

## Design decisions already made

- **Notebook opener = keybind** (default `N`), not a custom item: a client-only item is invisible to
  the multiplayer server we run. The crafted `seam:notebook` item comes later.
- **Container snapshots are singleplayer-first.** On a dedicated server a client-only mod cannot read
  a closed container's contents; it can only cache what the player opens. **Gather mode** (item-pickup
  tracking via a `ClientPlayNetworkHandler.onItemPickupAnimation` mixin) is the reliable multiplayer path.
- **HTTP = JDK `java.net.http.HttpClient`** (no ktor). **JSON = kotlinx-serialization** (provided by FLK
  at runtime — `compileOnly`, never bundle). Marshal results back to the client thread with
  `MinecraftClient.getInstance().execute { }`.

## Backend (mc-org, not here)

The sync backend **has landed**: **MCO-235** (read-only JSON API) + **MCO-236** (device-code auth)
shipped in mc-org as `472ce89`. The contract lives in
`mc-org/webapp/mc-web/src/main/kotlin/app/mcorg/api/ApiDtos.kt` — snake_case, `/api/v1`, bearer
token. `gg.seam.mod.api.ApiModels` is the client half of that same contract; **change the two
together.** Endpoints: `POST /auth/device-code`, `POST /auth/device-code/poll`,
`DELETE /auth/token`, `GET /worlds`, `GET /worlds/{id}/projects`,
`POST /projects/{id}/resources/sync`, `PUT /projects/{id}/tasks/{id}`.

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
here — normal branches. Issues live in the Linear **Mcorg** team, **Seam Companion Mod** project.
Do not create GitHub issues.
