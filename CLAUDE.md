# seam-mod — Claude context

Client-side **Fabric** Minecraft mod (the "Seam Companion"), part of the Seam workspace.
Bridges in-game resource tracking with the Seam webapp (`mc-org`, at `app.seam.gg`).

## Hard facts

- **Loader:** Fabric · **Minecraft:** `1.21.11` · **Language:** Kotlin via fabric-language-kotlin.
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

## Backend prerequisites (mc-org, not here)

Sync depends on two mc-org endpoints that do not exist yet: **MCO-235** (read-only JSON API) and
**MCO-236** (device-code auth). Phases 0–3 of this mod need no backend and can proceed in parallel.

## Workspace rules

Commits are independent per repo. The `mc-org` worktree-first / DB-isolation rules do **not** apply
here — normal branches. Issues live in the Linear **Mcorg** team, **Seam Companion Mod** project.
Do not create GitHub issues.
