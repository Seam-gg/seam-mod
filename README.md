# Seam Notebook (Minecraft mod)

Client-side Fabric mod that bridges in-game resource tracking with the [Seam](https://app.seam.gg)
webapp: inventory logging, container tagging, resource reconciliation, and task check-off.

- **Loader:** Fabric · **Minecraft:** 1.21.11 · **Language:** Kotlin (fabric-language-kotlin)
- **Distribution:** client-only (v1); multiplayer is supported but degraded (see reference doc)
- **Mod id:** `seam_notebook` · **Modrinth:** [`seam-notebook`](https://modrinth.com/mod/seam-notebook)

## Develop

Runs on **JDK 21 or newer** (Loom 1.17 + Gradle 9.5; Gradle ≥9.1 supports Java 25). The mod
itself is still compiled and run against **Java 21** — Minecraft 1.21.11's runtime — via the Loom
Java toolchain, regardless of which JDK runs Gradle.

```sh
./gradlew build       # compile + jar
./gradlew runClient   # launch a dev client
```

The built mod jar lands in `build/libs/`.

### WSL2 dev-client performance

On WSL2 the dev client is unusably slow (sub-1 fps) out of the box — two fixes, both needed:

1. **Force GPU rendering.** Mesa defaults to software (`llvmpipe`); force the WSL d3d12 driver:
   ```sh
   echo 'export GALLIUM_DRIVER=d3d12' >> ~/.bashrc && source ~/.bashrc
   ```
   Verify with `glxinfo -B | grep "renderer string"` → should name your GPU, not `llvmpipe`.
2. **Sodium** (already wired as `modLocalRuntime` in `build.gradle.kts`) — batches draw calls so
   the d3d12 translation overhead doesn't tank framerate. GPU-on alone is *not* enough; Minecraft's
   per-draw-call overhead still crushes it without Sodium.
3. **Fix audio** (prevents an OpenAL shutdown-watchdog crash). WSLg provides working PulseAudio, but
   Minecraft's OpenAL Soft doesn't select it by default — it fails to open a device, then hangs
   destroying the dead context on close. Force the PulseAudio backend:
   ```sh
   echo 'export ALSOFT_DRIVERS=pulse' >> ~/.bashrc && source ~/.bashrc
   ```

4. **Turn off Raw Input** (Options → Controls → Mouse Settings → Raw Input: OFF). WSLg's raw mouse
   motion isn't scaled by Minecraft's sensitivity slider, so the camera whips around no matter how
   far down you drag it — dragging sensitivity to the floor doesn't help, because the slider isn't
   in the path. With raw input off, GLFW's ordinary cursor-position path is used and sensitivity
   behaves normally (put it back to ~100% afterwards). In `run/options.txt`:
   `rawMouseInput:false`. Edit it only while the client is **closed** — Minecraft rewrites the file
   on exit.
5. **Install `wslview`** (`sudo apt install wslu`) if it's missing. Vanilla's "open link in browser"
   runs `xdg-open`, which a bare WSL distro doesn't have; the mod falls back to `wslview` /
   `explorer.exe` (see `gg.seam.mod.util.Browser`), so at least one of those must exist.

With `GALLIUM_DRIVER=d3d12` + Sodium + `ALSOFT_DRIVERS=pulse`, `./gradlew runClient` runs at full
speed with working sound and clean shutdown. (Verified on an RTX 3080 Ti.)

`build.gradle.kts` also sets both variables on the `runClient` run config when it detects WSL (via
`/proc/sys/kernel/osrelease`), so the dev client works even if the Gradle daemon was started from a
shell without them. The `~/.bashrc` exports are still worth having — they're what `glxinfo` and any
non-Gradle launch see.

### Pointing the dev client at a webapp

`runClient` defaults to `-Dseam.apiBaseUrl=http://localhost:8080` (run `mc-org` locally to link
against it). Override per run:

```sh
./gradlew runClient -PseamApiBaseUrl=https://app.seam.gg
```

Resolution order is `-Dseam.apiBaseUrl` → `SEAM_API_BASE_URL` → `api_base_url` in
`seam/config.json` → `https://app.seam.gg`. See `gg.seam.mod.api.SeamApi`.

## Versioning and releases

Versions are `<semver>+<minecraft_version>` — e.g. `0.2.0+1.21.11`. The semver half is
`modVersion` in `gradle.properties` and is the only part edited by hand; `build.gradle.kts`
appends the MC version, so the two can never disagree. Read the composed value back with
`./gradlew -q printVersion`.

Rebuilding unchanged code for a new Minecraft version therefore produces a new version string
without a fake patch bump. Pre-1.0 releases go to Modrinth as **beta** — the wire contract with
mc-org's `ApiDtos.kt` still moves in lockstep with the mod, so no jar yet promises to outlive a
webapp deploy.

**CI** (`.github/workflows/ci.yml`) builds and tests every push to `main` and every PR, and
attaches the remapped jar to the run.

**Releasing** is an annotated tag `v<version>` (e.g. `v0.2.0+1.21.11`), whose message is published
verbatim as the changelog. `.github/workflows/release.yml` rebuilds from the tag, refuses to
continue if the tag disagrees with the composed version or carries no message, and then **waits
for manual approval** — the upload job targets the `modrinth` GitHub Environment, where the
Modrinth token lives. Nothing publishes without a click.

Use the `/release` skill (`.claude/skills/release/`) to drive it; it owns the preflight checks,
the version bump, the changelog format, and the tag push.

## Docs

- **`docs/fabric-1.21.11-reference.md`** — version-correct Fabric 1.21.11 API reference and the
  version-churn hazard map. Read it before touching rendering, GUI, item, or event code.
- **`docs/v1-spec.md`** — the v1 product specification (drop the canonical file here).

Tracked in Linear under the **Seam Companion Mod** project (team `Mcorg`).
