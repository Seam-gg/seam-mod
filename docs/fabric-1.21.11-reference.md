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
| Distribution | **Client-only** today (`environment: "client"`) — **client + server** once MCO-534 lands | The *Shared Storage* project adds a `main` entrypoint that reads tagged containers server-side. Until that ships, the jar is client-only; §9 documents what it will need. |
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
| H11 | **HUD rendering** — `HudRenderCallback` superseded by `…rendering.v1.hud.HudElementRegistry` + `HudElement.render(DrawContext, RenderTickCounter)`, ordered against `VanillaHudElements.*` | **fabric-api ≥1.21.6** | The storage HUD (§9). Verified against `fabric-rendering-v1` 16.2.10 in this project's own dependency graph. |
| H12 | **Command permissions are predicates, not ints** — `ServerCommandSource.hasPermissionLevel(int)` **removed**; use `CommandManager.OWNERS_CHECK.allows(source.permissions)` and friends from `net.minecraft.command.permission` | **1.21.11** | `/seam` (§10). Every command tutorial in existence calls the removed method, so this fails to compile the moment you copy one. |

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
  - ⚠ **1.21.11 gotcha (verified by crash): do NOT call `renderBackground()` inside your `render()`.** The framework now applies the screen backdrop (a blur) *before* invoking `render()`, and the blur may only be applied once per frame — a manual `renderBackground()` call throws `IllegalStateException: Can only blur once per frame`. Draw your panel/content directly, and render widgets on top yourself (`for (e in children()) (e as? Drawable)?.render(...)`) rather than relying on the old `super.render()` background pass.
- **`DrawContext` (verified 1.21.11):**
  - Text: `drawText(textRenderer, str/Text, x, y, colorARGB, shadow)` / `drawTextWithShadow(...)`. **Color is ARGB — set alpha (`0xFF……`) or text is invisible.** Measure with `textRenderer.getWidth(...)`, row height `textRenderer.fontHeight`.
  - Rectangles / progress bars: **5-arg `fill(x1,y1,x2,y2,colorARGB)`** — all we need. Also `drawHorizontalLine`/`drawVerticalLine`. ⚠ **`drawBorder` is NOT present** — draw 4 fills.
  - Textures: `drawTexture`/`drawGuiTexture` need a `RenderPipeline` first arg (H4). **Avoid for v1.**
  - Scrolling: wrap in `enableScissor(...)/disableScissor()`.
- **Widgets** (`net.minecraft.client.gui.widget`, register with `addDrawableChild`): `ButtonWidget.builder(...)`; `CheckboxWidget.builder(text, textRenderer).checked(...).callback{ _, checked -> }` for tasks; `SliderWidget` is **abstract — subclass** (`value` is 0..1, override `updateMessage`/`applyValue`); `CyclingButtonWidget.onOffBuilder(...)` for toggles and — since **there is no native dropdown** — for the project selector (small lists) or a custom popup list (long lists); `TextFieldWidget` for search.
- **Tabs (settings):** vanilla `TabNavigationWidget` + `TabManager` (idiomatic, boilerplate-heavy) OR a manual `activeTab` + re-init (lighter, better for a custom notebook aesthetic). Scrollable rows with controls → `ElementListWidget`; selectable rows → `AlwaysSelectedEntryListWidget`.
- **Input:** H3 — `keyPressed(KeyInput)`, mouse handlers take `Click`/`MouseInput`. Verify accessor names in-IDE.
- **Fonts:** IBM Plex Mono is possible via a `ttf` font provider (`assets/seam/font/…`, select with `Text.styled{ it.withFont(...) }`) but TTF is anti-aliased/off-grid and fiddly. **Recommend vanilla font for v1**; reserve Plex for web surfaces.
- **Container-tag picker overlay:** a non-pausing `Screen` (`shouldPause()=false`, render only a small card) — reuses widgets/focus/close, still modal. A true no-input HUD chip does **not** use `HudRenderCallback` any more — that is superseded (H11); see §9.

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

**The table above is still true and still load-bearing.** What changed (2026-09-06) is the answer to it.

This section used to conclude that container snapshots were singleplayer-only, that multiplayer degraded to open-and-cache with staleness, and that **gather mode (pickup tracking) was the reliable multiplayer path**. The *Shared Storage* project takes the other road: rather than approximate a closed container from the client, a **server half reads it properly** (§9). Pickup tracking and inventory scanning were cancelled as counting mechanisms — the count is what is in tagged containers, nothing else.

So the constraint is unchanged and the mitigation is inverted: **do not** reach for `onItemPickupAnimation` or open-and-cache to count resources. Read the container on the side that can see it.

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

## 9. Server side: containers & the HUD

> **Built 2026-09-08** (MCO-534, MCO-260, MCO-535) and exercised against a real mc-org; the HUD half (MCO-537) is still forward-looking. Recorded here rather than in an issue because these are facts about 1.21.11 and they outlive the issues.

Everything here was checked against `minecraft-merged-1.21.11-…-yarn.1.21.11+build.6` and the Fabric API jars in this project's dependency graph — **not** against the docs site (H10).

### The mod becomes client + server (MCO-534)

`environment: "*"` with both a `client` and a `main` entrypoint. `main` runs on dedicated servers **and in singleplayer's integrated server**, which is what makes SP and MP one code path rather than two implementations. The two halves never speak to each other — no custom packets — they meet in the mc-org API.

⚠ Code in the shared sweep package must not reference `MinecraftClient`, or it will not class-load on a dedicated server. This fails late and loudly; the dedicated-server load test is the guard.

### Reading a container server-side

| need | API | note |
|---|---|---|
| chunk guard | `ServerWorld.isChunkLoaded(long)` + `ChunkPos.toLong(BlockPos)` | An unloaded container **cannot have changed** — keep its last reading rather than re-reading it. |
| the inventory | `world.getBlockEntity(pos) as? Inventory` | Must run **on the server thread** — world access is not thread-safe. |
| double chests | `ChestBlock.getInventory(ChestBlock, BlockState, World, BlockPos, boolean)` | Returns the **combined** `DoubleInventory` from *either* half — so reading both halves double-counts. Read one and report the other as empty; see the warning below about picking it. |
| shulker contents | `stack.get(DataComponentTypes.CONTAINER)` → `ContainerComponent.streamNonEmpty()` | One level deep; vanilla shulkers do not nest. Without this a shulker-based base reports near-zero. |
| the tick hook | `ServerTickEvents.END_SERVER_TICK` | Also `START_SERVER_TICK`, `START_WORLD_TICK`, `END_WORLD_TICK`. |

### Getting off the server thread and back on again

The sweep reads on the server thread and does its HTTP off it, so results have to come back. Both
routes are real; they are not equivalent.

| route | API | when |
|---|---|---|
| hand a task to the server thread | `MinecraftServer.execute(Runnable)` (inherited from `ThreadExecutor`, which implements `java.util.concurrent.Executor`) | You hold a `MinecraftServer` and just want the work to happen there. `isOnThread()` tells you whether you already are. |
| queue it and drain it in the tick | a `ConcurrentLinkedQueue` the tick loop empties first thing | You want the same code to run in a unit test, where no `MinecraftServer` can be constructed. This is what `ReporterService` does. |

⚠ **The bug this prevents is not subtle.** An `HttpClient` callback fires on the client's own
executor, not the server thread. A plain `HashMap` written from both is not a race that shows up as
a slightly stale read — a concurrent resize corrupts the map or spins a thread at 100%. If a field
is touched from a tick, only a tick may write it.

⚠ **Which half of a double chest holds the counts must be decided per read, from what is actually
there — not once from the coordinates.** A fixed choice looks correct and fails two ways: break the
chosen half and the pair reports missing while a perfectly readable chest stands next to it; and
when the pair straddles a chunk border (roughly one in eight) the chosen half's chunk can unload
while the other stays loaded, so the counts move to the sibling while the chosen half keeps its own
copy — and the pair is counted twice.

### HUD (H11)

`HudRenderCallback` is superseded. The current API is `net.fabricmc.fabric.api.client.rendering.v1.hud`:

```kotlin
HudElementRegistry.attachElementAfter(
    VanillaHudElements.SCOREBOARD,
    Identifier.of(SeamClient.MOD_ID, "storage_hud"),
) { context: DrawContext, tick: RenderTickCounter -> StorageHud.render(context) }
```

- `HudElement.render(DrawContext, RenderTickCounter)` — one method.
- Ordering: `addFirst` / `addLast` / `attachElementBefore` / `attachElementAfter`, anchored on `VanillaHudElements` (`CROSSHAIR`, `HOTBAR`, `SCOREBOARD`, `CHAT`, `BOSS_BAR`, `STATUS_EFFECTS`, …). `removeElement` / `replaceElement` also exist.
- **`DrawContext.drawItem(ItemStack, x, y)` is present** — real item icons for free, with no texture work and therefore no H4 `RenderPipeline` argument.
- Respect `client.options.hudHidden` (F1), and hide while a `Screen` is open.

Still the highest-churn area in the codebase (H1). **Spike it before building on it.**

---

## 10. Server commands, and the permission rewrite (H12)

> Added 2026-09-08 while building `/seam` (MCO-534). Verified by `javap` against
> `minecraft-merged-1.21.11-…-yarn.1.21.11+build.6` and by compiling — not from a tutorial.

Registration is unchanged from the familiar shape, via `fabric-command-api-v2` (on the classpath
already through the full `fabric-api` dependency):

```kotlin
CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
    dispatcher.register(
        CommandManager.literal("seam")
            .requires { CommandManager.OWNERS_CHECK.allows(it.permissions) }
            .then(CommandManager.literal("status").executes { ... })
            .then(
                CommandManager.literal("connect")
                    .then(CommandManager.argument("world_id", IntegerArgumentType.integer(1))
                        .then(CommandManager.argument("token", StringArgumentType.string())
                            .executes { ... })),
            ),
    )
}
```

### ⚠ `hasPermissionLevel(int)` is gone

**This is the trap, and it is not in any tutorial yet.** `ServerCommandSource.hasPermissionLevel(int)`
— the thing every command example calls — **does not exist on 1.21.11**. It was replaced by a
predicate system in `net.minecraft.command.permission`:

| old | 1.21.11 |
|---|---|
| `source.hasPermissionLevel(4)` | `CommandManager.OWNERS_CHECK.allows(source.permissions)` |
| `source.hasPermissionLevel(3)` | `CommandManager.ADMINS_CHECK.allows(source.permissions)` |
| `source.hasPermissionLevel(2)` | `CommandManager.GAMEMASTERS_CHECK.allows(source.permissions)` |
| `source.hasPermissionLevel(1)` | `CommandManager.MODERATORS_CHECK.allows(source.permissions)` |
| *(no gate)* | `CommandManager.ALWAYS_PASS_CHECK` |

The pieces, for when the ready-made constants are not enough:

- `ServerCommandSource.getPermissions(): PermissionPredicate` (Kotlin: `source.permissions`) — what
  the caller *has*. Also `withPermissions` / `withAdditionalPermissions` to derive a source.
- `PermissionCheck.allows(PermissionPredicate): Boolean` — what a command *requires*.
  `CommandManager` exposes the five constants above; they are `PermissionCheck`, not `Predicate`, so
  they cannot be passed straight to `.requires { }` — ask them.
- `PermissionLevel` is still an enum with the familiar rungs — `ALL, MODERATORS, GAMEMASTERS,
  ADMINS, OWNERS` — plus `fromLevel(int)`, `getLevel()` and `isAtLeast(PermissionLevel)`, if a
  numeric level has to be bridged.

### Brigadier string arguments — a URL will not parse with `string()`

Not a 1.21.11 change, but the same class of trap and it cost a real debugging round here.
`StringArgumentType` has three modes, and the difference bites on anything URL-shaped:

| type | reads |
|---|---|
| `word()` | one unquoted word: `[A-Za-z0-9_.+-]` only |
| `string()` | a **quoted** string, or an unquoted word — same restricted alphabet |
| `greedyString()` | the rest of the line, verbatim |

So `/seam connect 3 tok http://localhost:8080` fails with **"Expected whitespace to end one
argument, but found trailing data"**, pointing at the `:` — because `string()` fell back to word
parsing and `:` and `/` are not word characters. Either quote the argument or, for a trailing one,
use `greedyString()`. Found by running the command, not by reading it.

### Command sources and secrets

`ServerCommandSource.entity` is null when the command came from the server console. Worth checking
before accepting anything secret: Minecraft logs commands players run, so a token typed in-game is
in the server log afterwards. `/seam connect` allows it and says so rather than refusing, because
the remedy (revoke and re-mint) is cheap and only obvious if someone points it out.

Feedback is `source.sendFeedback({ Text.literal(...) }, broadcastToOps)` — the message is a
**supplier**, evaluated only if it is actually going to be shown — and `source.sendError(Text)`.

---

## Source index
Full per-slice source URLs are in the six agent transcripts. Primary anchors: `meta.fabricmc.net`, `maven.fabricmc.net/docs/yarn-1.21.11+build.3` (and build.4/.6), `docs.fabricmc.net/develop` (Mojmap — translate), Fabric release notes 2025-09-23 (1.21.9/.10) and 2025-12-05 (1.21.11), fabric-api issues #1130 (pickup) and #4902 (WorldRenderEvents redesign).
