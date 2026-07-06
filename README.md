# Seam Companion (Minecraft mod)

Client-side Fabric mod that bridges in-game resource tracking with the [Seam](https://app.seam.gg)
webapp: inventory logging, container tagging, resource reconciliation, and task check-off.

- **Loader:** Fabric · **Minecraft:** 1.21.11 · **Language:** Kotlin (fabric-language-kotlin)
- **Distribution:** client-only (v1); multiplayer is supported but degraded (see reference doc)

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

With both, `./gradlew runClient` runs at full speed. (Verified on an RTX 3080 Ti.)

## Docs

- **`docs/fabric-1.21.11-reference.md`** — version-correct Fabric 1.21.11 API reference and the
  version-churn hazard map. Read it before touching rendering, GUI, item, or event code.
- **`docs/v1-spec.md`** — the v1 product specification (drop the canonical file here).

Tracked in Linear under the **Seam Companion Mod** project (team `Mcorg`).
