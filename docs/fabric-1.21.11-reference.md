# Seam Companion Mod — Fabric 1.21.11 Implementation Reference

> Consolidated from a six-agent documentation sweep (2026-07-06), each slice verified
> against official Fabric/Yarn sources for the **exact target build**. This is the
> version-correct ground truth to code against — Minecraft/Fabric APIs churn hard, and
> several of the pieces this mod needs **changed within the last few months**. Treat every
> ⚠ flag as "verify against a live dev environment before shipping."

## 0. Target platform (settled)

| Property | Value | Why |
|---|---|---|
| Mod loader | **Fabric** | Matches the Seam server (`seam-server-dashboard`) |
| Minecraft | **1.21.11** | Server's `client-mods.json` / `fly.toml` pin this |
| Language | **Kotlin** via fabric-language-kotlin | Per spec |
| Distribution | **Client-only** (v1); degraded multiplayer | Per spec; server component is v2, "script-first" |
| HTTP | **JDK `java.net.http.HttpClient`** (not ktor) | MC runs on Java 21; a handful of JSON calls don't justify Jar-in-Jar |
| JSON | **kotlinx-serialization** | Already bundled by FLK — do NOT re-bundle |

### Verified version coordinates (`gradle.properties`)
Re-verify at `https://fabricmc.net/develop` / `meta.fabricmc.net` before starting — Fabric re-cuts these on nearly every MC build.

```properties
minecraft_version=1.21.11
yarn_mappings=1.21.11+build.6          # highest stable build; agents also saw build.3/build.4
loader_version=0.19.3
fabric_version=0.141.4+1.21.11         # Fabric API (rendering agent saw 0.137.0+1.21.11 — take newest)
fabric_language_kotlin_version=1.13.12+kotlin.2.4.0
kotlin_version=2.4.0                    # MUST match FLK's bundled Kotlin (the +kotlin.X suffix)
loom_version=1.17
```
Toolchain baseline: **Loom 1.17 + Gradle 9.5**, runs on **JDK 21+** (Gradle ≥9.1 supports Java 25).
The mod *targets* Java 21 (MC 1.21.11's runtime) via `jvmToolchain(21)` regardless of the JDK running Gradle.

---

## 1. THE VERSION-CHURN HAZARD MAP (read this first)

The single most valuable output of the sweep. Every one of these will silently break code copied from older tutorials.

| # | What changed | Introduced | Impact on us |
|---|---|---|---|
| H1 | **Renderer rewrite** — `WorldRenderEvents` removed then reintroduced/redesigned; `drawTexture`, `BlockEntityRenderer`, entity nametag all re-signed onto a deferred `OrderedRenderCommandQueue` | **1.21.9 → reintroduced 1.21.10** | Floating labels + any custom texture draw. HIGHEST RISK. |
| H2 | **`ClickEvent` / `HoverEvent` → sealed interfaces of records** (`ClickEvent.OpenUrl(URI)`, `HoverEvent.ShowText(Text)`); old `new ClickEvent(Action, String)` gone | **1.21.5** | Clickable `[Seam]` chat links. |
| H3 | **Input API** — `keyPressed(KeyInput)` + `Click`/`MouseInput` wrappers replace raw `(int keyCode, …)` | **1.21.9** | Every Screen key/mouse handler, overlay hit-testing. |
| H4 | **`drawTexture`/`drawGuiTexture` take a `RenderPipeline` first arg** | **1.21.6** | GUI textures. Mitigation: build v1 UI from `fill` + text + vanilla widgets, avoid custom textures. |
| H5 | **`ActionResult` merge** — `Item.use` returns `ActionResult` (not `TypedActionResult`); `ItemActionResult` gone | **1.21.2** | Item use-handlers. |
| H6 | **Item registry key mandatory** — `Item.Settings.registryKey(key)` required or registration throws | **1.21.0** | Notebook item registration. |
| H7 | **`PlayerInventory.armor` / `.offHand` fields removed** — equipment moved to internal `EntityEquipment` | **1.21.2** | Inventory scan — iterate whole inventory or use `getEquippedStack`. |
| H8 | **Recipe folder singular** `data/<ns>/recipe/` + string ingredients + `result.id` | **1.21.0** | Notebook crafting recipe. |
| H9 | **`assets/<ns>/items/<name>.json`** item-model definition required | **1.21.4** | Notebook item asset. |
| H10 | **Fabric docs site now renders Mojmap + a newer-snapshot render model** (`extractRenderState`, `Component`, `addRenderableWidget`) that does NOT match 1.21.11 Yarn | current | Don't copy the docs site's render/GUI prose verbatim — translate names, ignore `extractRenderState`. |

**Mappings:** all snippets below are **Yarn**. If the build uses Mojmap, translate (`MinecraftClient`→`Minecraft`, `Text`→`Component`, `ButtonWidget`→`Button`, `addDrawableChild`→`addRenderableWidget`, `Identifier`→`ResourceLocation`, etc.). Pick one and stay consistent.

---

## 2. Toolchain & project setup

- `settings.gradle.kts`: `pluginManagement` repos = Fabric maven + gradlePluginPortal.
- `build.gradle.kts` plugins: `fabric-loom`, `kotlin("jvm")`, `kotlin("plugin.serialization")` (compiler plugin, still needed even though the runtime lib is provided).
- **fabric-language-kotlin already provides at runtime** (do NOT `include`/shade these): kotlin-stdlib/reflect (2.4.0), kotlinx-coroutines (1.11.0, incl. jdk8 → gives `CompletableFuture.await()`), **kotlinx-serialization-core/json/cbor (1.11.0)**, kotlinx-datetime (0.8.0), atomicfu (0.33.0), kotlinx-io (0.9.0). Depend on serialization `compileOnly` at the matching version.
- **If we ever add ktor** (we're choosing not to): `include(...)` each ktor jar (Jar-in-Jar; **non-transitive** — list every sub-module), and exclude kotlinx-* to avoid double-bundling FLK's copies. Shadow plugin is discouraged with Loom.

### fabric.mod.json (client-only, Kotlin)
```json
{
  "schemaVersion": 1,
  "id": "seam",
  "version": "${version}",
  "environment": "client",
  "entrypoints": {
    "client": [{ "adapter": "kotlin", "value": "gg.seam.mod.SeamClient" }]
  },
  "depends": {
    "fabricloader": ">=0.19.3",
    "minecraft": "~1.21.11",
    "java": ">=21",
    "fabric-api": "*",
    "fabric-language-kotlin": ">=1.13.12+kotlin.2.4.0"
  }
}
```
`"adapter": "kotlin"` routes the entrypoint through FLK. A Kotlin `object` works as the entrypoint value. `runClient` to launch; only method-body hot-swap works — structural changes / mixin / json changes need a restart.

> ⚠ **Item-registration side note (H6 + client-only):** an item registered only in `onInitializeClient` works in **singleplayer** but is **unknown to a multiplayer server** (registry sync excludes it) — so on our own Fabric server the crafted notebook item would not naturally exist. See §3 for the decision.

---

## 3. Notebook item, recipe & container interaction

### Item registration (Yarn, 1.21.x)
```kotlin
val key = RegistryKey.of(RegistryKeys.ITEM, Identifier.of("seam", name))
val item = factory(Item.Settings().maxCount(1).registryKey(key))   // H6: registryKey REQUIRED
Registry.register(Registries.ITEM, key, item)
```
Assets: `models/item/notebook.json` (`item/generated` + `layer0`), **`items/notebook.json`** (H9, 1.21.4+ model definition), `textures/item/notebook.png`, `lang/en_us.json` (`item.seam.notebook`).

### Recipe (H8 — singular folder, string ingredients)
`data/seam/recipe/notebook.json`:
```json
{ "type": "minecraft:crafting_shapeless",
  "ingredients": ["minecraft:book", "minecraft:iron_nugget", "minecraft:ink_sac"],
  "result": { "id": "seam:notebook", "count": 1 } }
```

### Use interactions (H5 — merged `ActionResult`)
- **Right-click to open notebook:** override `Item.use(world, user, hand): ActionResult`, guard `world.isClient`, `MinecraftClient.getInstance().setScreen(...)`, return `ActionResult.SUCCESS`. (Or a `UseItemCallback` / keybind — see below.)
- **Sneak + right-click a container:** `Item.useOnBlock(ctx)` or Fabric `UseBlockCallback`. Detect container via `world.getBlockEntity(pos) is Inventory` (chest/barrel/shulker/hopper all implement it), or narrow to `ChestBlockEntity`/`BarrelBlockEntity`/`ShulkerBoxBlockEntity`/`HopperBlockEntity`. Return `SUCCESS` to consume so the vanilla GUI doesn't open. `ChestBlockEntity` covers trapped chests too.
- Return values: `SUCCESS` (client also sends a packet), `FAIL` (cancel, **no** packet — use for pure-client open), `PASS` (fall through).

> **DECISION (item vs keybind for v1):** because a client-only item won't exist on the MP server we run, the pragmatic v1 opener is a **keybind** (`KeyBindingHelper` + `ClientTickEvents`), with the crafted `seam:notebook` item added when we commit to an item-registration approach (keybind-first is simpler and unblocks the whole UI). The spec treats the physical notebook as core identity, so this is a real trade-off to make deliberately, not silently.

---

## 4. Notebook GUI (custom `Screen`) — verified vs `yarn 1.21.11+build.3`

- Subclass `net.minecraft.client.gui.screen.Screen`. Build widgets in `init()` (not the constructor — `width`/`height` valid only there; re-called on resize). `render(DrawContext, mouseX, mouseY, delta)`; `shouldPause() = false` to keep the world live; `close()` → `client?.setScreen(parent)`. Open via `MinecraftClient.getInstance().setScreen(NotebookScreen())`.
- **`DrawContext` (verified 1.21.11):**
  - Text: `drawText(textRenderer, str/Text, x, y, colorARGB, shadow)` / `drawTextWithShadow(...)`. **Color is ARGB — set alpha (`0xFF……`) or text is invisible.** Measure with `textRenderer.getWidth(...)`, row height `textRenderer.fontHeight`.
  - Rectangles / progress bars: **5-arg `fill(x1,y1,x2,y2,colorARGB)`** — all we need. Also `drawHorizontalLine`/`drawVerticalLine`. ⚠ **`drawBorder` is NOT present** — draw 4 fills.
  - Textures: `drawTexture`/`drawGuiTexture` need a `RenderPipeline` first arg (H4). **Avoid for v1.**
  - Scrolling: wrap in `enableScissor(...)/disableScissor()`.
- **Widgets** (`net.minecraft.client.gui.widget`, register with `addDrawableChild`): `ButtonWidget.builder(...)`; `CheckboxWidget.builder(text, textRenderer).checked(...).callback{ _, checked -> }` for tasks; `SliderWidget` is **abstract — subclass** (`value` is 0..1, override `updateMessage`/`applyValue`); `CyclingButtonWidget.onOffBuilder(...)` for toggles and — since **there is no native dropdown** — for the project selector (small lists) or a custom popup list (long lists); `TextFieldWidget` for search.
- **Tabs (settings):** vanilla `TabNavigationWidget` + `TabManager` (idiomatic, boilerplate-heavy) OR a manual `activeTab` + re-init (lighter, better for a custom notebook aesthetic). Scrollable rows with controls → `ElementListWidget`; selectable rows → `AlwaysSelectedEntryListWidget`.
- **Input:** H3 — `keyPressed(KeyInput)`, mouse handlers take `Click`/`MouseInput`. Verify accessor names in-IDE.
- **Fonts:** IBM Plex Mono is possible via a `ttf` font provider (`assets/seam/font/…`, select with `Text.styled{ it.withFont(...) }`) but TTF is anti-aliased/off-grid and fiddly. **Recommend vanilla font for v1**; reserve Plex for web surfaces.
- **Container-tag picker overlay:** a non-pausing `Screen` (`shouldPause()=false`, render only a small card) — reuses widgets/focus/close, still modal. A true no-input HUD chip would use `HudRenderCallback`.

**v1 GUI strategy:** build entirely from `fill` rectangles + text + vanilla widgets. This dodges H4 (RenderPipeline textures) and the TTF font work.

---

## 5. Floating container labels (world-space) — HIGHEST version risk (H1)

- **Approach: `WorldRenderEvents.AFTER_ENTITIES`**, iterate our own tracked `BlockPos` set. Do **NOT** override vanilla `BlockEntityRenderer`s — the registry is a single map; re-registering `CHEST` clobbers the vanilla animated-lid renderer, and hoppers/barrels have no BER anyway.
- `AFTER_ENTITIES` is the first phase where `ctx.matrixStack()` is non-null; use `ctx.consumers()` (`VertexConsumerProvider`) for **immediate-mode** `TextRenderer.draw(...)`.
- **Billboard math:** translate by `(worldPos - cameraPos)` (world render is **camera-relative**), `matrices.multiply(camera.rotation)` (JOML `Quaternionf`), `matrices.scale(-0.025f, -0.025f, 0.025f)` (flip + px→blocks), draw centered at `-font.getWidth/2`.
- **Text draw (verified 1.21.11):** `font.draw(text, x, y, colorARGB, shadow, matrix, consumers, TextLayerType, bgColorARGB, light)`. `TextLayerType`: `NORMAL` / `SEE_THROUGH` / `POLYGON_OFFSET`. Light = `LightmapTextureManager.pack(15,15)` for full-bright.
- **Distance fade:** distance from `camera.pos`; linear ramp full ≤8 blocks → 0 at 12; apply alpha to high byte of text+bg color; skip if `alpha ≤ 4`. Cheap rejects in order: toggle → distance → `world.isChunkLoaded(x>>4,z>>4)` → alpha → optional `frustum().isVisible`.
- ⚠ **VERIFY on live 1.21.11:** (1) whether to manually flush `(consumers as? Immediate)?.draw()` or let the pipeline flush — flush timing changed in the rewrite; test both if text is missing/z-fighting. (2) scale sign if text renders mirrored. (3) the reintroduced `WorldRenderContext` accessors were confirmed against fabric-api 0.129.0+1.21.7, not the 0.137.0+1.21.11 javadoc (not hosted) — reconfirm.

This is the one feature to **prototype in a throwaway dev world first** before committing to the design.

---

## 6. Inventory scanning & events — and the multiplayer truth

### The load-bearing fact
A client-only mod sees **only what the vanilla client is told over the network.** A closed container's item contents are **not sent to the client on a dedicated server**.

| Data | Singleplayer | Multiplayer (our server), client-only |
|---|---|---|
| Player's own inventory | ✅ full | ✅ full |
| Ender chest | ✅ full | ⚠ only while its screen open/recently synced |
| Closed container contents (`getBlockEntity(pos)`) | ✅ full (same-JVM server) | ❌ **empty/0 — never sent** |
| Open container contents (`ScreenHandler.slots`) | ✅ | ✅ that one container, while open |
| Item pickup events | ✅ | ✅ (packet mixin, below) |
| Container open/close | ✅ `ScreenEvents` | ✅ |

**Consequence for the product:** the spec's "walk all tagged containers and snapshot them" is effectively **singleplayer-only**. On the server we run, container tracking degrades to **open-and-cache with staleness**. **Gather mode (pickup tracking) is the reliable multiplayer path.** Server-side container scanning is exactly the piece that wants a **server-side script** (RCON / world-data), matching the "run server bits as a script first" plan.

### How
- **Player inventory:** `client.player.inventory` implements `Iterable<ItemStack>` — iterate it (H7: don't touch `.armor`/`.offHand`). Count by id: `Registries.ITEM.getId(stack.item)`. Test with `stack.isOf(...)` / `stack.isEmpty` (never `==`). Ender chest: `player.enderChestInventory`.
- **Container (single):** `world.getBlockEntity(pos) as? Inventory` after `world.isChunkLoaded(pos)`.
- **Double chest (combined):** `ChestBlock.getInventory(block, state, world, pos, /*ignoreBlocked=*/true)` → `DoubleInventory` spanning both halves (or the single chest). Reading one half's BE gives only half.
- **Pickup events (no Fabric event exists — issue #1130 open since 2020):** mixin `ClientPlayNetworkHandler.onItemPickupAnimation(ItemPickupAnimationS2CPacket)` at `TAIL`; filter `packet.collectorEntityId == player.id`; look up `world.getEntityById(packet.entityId) as? ItemEntity`, read `.stack`; count `packet.stackAmount`. Works SP + MP. ⚠ verify the mixin method descriptor against the remapped jar.
- **Container open (for the staleness cache):** Fabric `ScreenEvents.AFTER_INIT` (no mixin); match `screen is HandledScreen<*>`; read `screen.screenHandler.slots`, skipping slots whose `.inventory === player.inventory`. **Snapshot on close/tick, not at AFTER_INIT** (slots sync 1–2 ticks later). `GenericContainerScreenHandler.inventory` for chests/barrels.

---

## 7. HTTP, auth, chat & persistence

- **HTTP: JDK `java.net.http.HttpClient`** — one shared instance, `sendAsync(req, BodyHandlers.ofString())` → `CompletableFuture`. Zero deps. `Authorization: Bearer <token>`.
- **Threading:** never block the client thread. Do HTTP + JSON parse off-thread; marshal UI/world/chat mutations back with **`MinecraftClient.getInstance().execute { }}`** (client is a `ReentrantThreadExecutor`).
- **JSON:** kotlinx-serialization (FLK-provided). `Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }`. `@Serializable` data classes with `@SerialName` for snake_case.
- **Device-code auth (RFC 8628):** POST device-code → show `user_code` + verification link in chat → poll token endpoint off-thread honoring `interval`, `authorization_pending` (keep polling), `slow_down` (+5s), `access_denied`/`expired_token` (stop). FLK bundles `kotlinx-coroutines-jdk8` → `CompletableFuture.await()` for a clean suspend loop; or `ScheduledExecutorService` + `thenAccept`.
- **Chat (client-only, no server round-trip):** `MinecraftClient.getInstance().inGameHud.chatHud.addMessage(Text)` (guard nulls, on client thread). Build `Text.literal("[Seam] ").styled{ it.withColor(TextColor.fromRgb(0x…)).withBold(true) }.append(...)` with design-token colors.
- **Clickable link (H2):** `ClickEvent.OpenUrl(URI.create(url))` (URI, not String) inside `.withClickEvent(...)`; `HoverEvent.ShowText(Text)`. There is **no clean client-only "run my lambda on click"** — use `OpenUrl` to the notebook URL; `ClickEvent.Custom` (1.21.6+) is uncertain for pure-client handling.
- **Persistence:** `FabricLoader.getInstance().gameDir.resolve("seam")` (or `configDir`). Global token → `seam/config.json`. Per-world/server binding files keyed by SP world-dir name or a **SHA-256 hash of `host:port`** for MP. Atomic write: temp file → `Files.move(..., REPLACE_EXISTING)`. File I/O off the client thread.

---

## 8. Consolidated open decisions & risks

| # | Decision / risk | Recommendation |
|---|---|---|
| D1 | Notebook opener: crafted item vs keybind | ✅ **DECIDED: keybind for v1** (client item invisible to the MP server we run); add the crafted `seam:notebook` item later when a registration approach is chosen |
| D2 | Container snapshots barely work on MP | **Gather-mode first**; container-snapshot is SP-first; server-side scan = future **script** |
| D3 | Floating-label rendering (H1) | Prototype in a throwaway world before design lock; validate flush/scale/accessors live |
| D4 | Backend prerequisites | **MCO-235 (JSON API)** + **MCO-236 (device-code auth)** must land before any sync; they're mc-org work (worktree-first) |
| D5 | GUI textures/fonts | v1 = `fill` + text + vanilla widgets + vanilla font; defer custom art |
| D6 | Verify all ⚠ signatures | Stand up the dev env early; let the compiler + a smoke world confirm the 1.21.11 build |

## Source index
Full per-slice source URLs are in the six agent transcripts. Primary anchors: `meta.fabricmc.net`, `maven.fabricmc.net/docs/yarn-1.21.11+build.3` (and build.4/.6), `docs.fabricmc.net/develop` (Mojmap — translate), Fabric release notes 2025-09-23 (1.21.9/.10) and 2025-12-05 (1.21.11), fabric-api issues #1130 (pickup) and #4902 (WorldRenderEvents redesign).
