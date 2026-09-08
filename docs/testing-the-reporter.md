# Testing the reporter in a real instance

The Phase B reporter (MCO-534, MCO-260, MCO-535) reads tagged containers and pushes what it finds
to mc-org. This is how to watch that happen end to end.

**The one thing that is not built yet:** tagging a chest from inside Minecraft. That gesture is
MCO-261, in Phase C. Until it lands, tags are created from a terminal with
[`scripts/seam-tag.sh`](../scripts/seam-tag.sh) — the same `/api/v1` endpoints the gesture will
call. Everything else below is the real thing.

## 0. A webapp to talk to

Local is the right place to start: it is the same code as production, and a mistake costs nothing.

```bash
cd mc-org/webapp
./scripts/start-db.sh          # postgres in docker
./scripts/migrate-locally.sh   # schema to v2.69.0 or later — the reporter needs it
./scripts/run.sh --env local   # http://localhost:8080
```

## 1. A world, a project, and something to measure

The reporter only reports items a project actually asks for — its gathering targets plus its plan
items. **A project with no targets measures nothing**, correctly and silently, so pick one in
`RESOURCE_GATHERING` with resources on it. `scripts/seam-tag.sh projects <world_id>` lists them
with their stage.

## 2. Tag some containers

```bash
cd seam-mod
export SEAM_API_BASE_URL=http://localhost:8080   # omit for app.seam.gg
./scripts/seam-tag.sh login                      # device-code, same flow as the mod
./scripts/seam-tag.sh worlds
./scripts/seam-tag.sh projects 1
./scripts/seam-tag.sh tag 1 2 100 64 200 barrel  # world, project, x, y, z, kind
```

Tag positions that exist in the world you are about to load — the sweep reports a tag pointing at
thin air as `missing`, which is correct and unexciting.

**A joined double chest is two tags sharing one `group_key`**, and the group key must be one of the
two positions:

```bash
./scripts/seam-tag.sh tag 1 2 10 64 20 chest minecraft:overworld 10,64,20
./scripts/seam-tag.sh tag 1 2 11 64 20 chest minecraft:overworld 10,64,20
```

The sweep reads such a pair once and reports the other half as empty. Tagging only one half also
works; tagging both without a shared key would count the pair twice, and the webapp's adjacency
check on `group_key` is what stops a typo doing something worse.

## 3. Mint a reporter token

In the webapp: **world settings → Connected server → Generate token**. It is shown once.

It is world-scoped and cannot do anything else — in particular it **cannot tag**, because tagging
records who did it. That is why step 2 signs in as you and this step does not.

## 4. Connect the server

**Use `runServer` and join it with your ordinary Minecraft client.** Not `runClient` — for the
reporter it is worse in every way that matters:

- It is the **real dedicated-server path**, not singleplayer's integrated one.
- `/seam connect` belongs in a console. Minecraft logs commands players type, so a token typed in
  chat lands in the server log; `/seam` says so when you do it, and the fix is to revoke and
  re-mint.
- **Your client needs no mod.** The mod registers no blocks, items or packets and the two halves
  never speak to each other, so a vanilla 1.21.11 client connects to a server running it.
- It sidesteps the dev client entirely — including WSL2's raw-mouse problem (README § WSL2
  dev-client performance).

```bash
./gradlew runServer          # localhost:25565, console on stdin
```

Then, in that console:

```
/seam connect 1 <token>
```

`runServer` and `runClient` both set `-Dseam.apiBaseUrl=http://localhost:8080`, so the URL argument
is only needed to point somewhere else (`-PseamApiBaseUrl=…` changes the default). A **shipped**
jar with no override defaults to `https://app.seam.gg`.

`run/server.properties` is `online-mode=true`, so join with your real account. To run `/seam`
in-game rather than in the console, `op <your-name>` first — but prefer the console, for the reason
above.

<details>
<summary>Singleplayer instead</summary>

`main` also runs in singleplayer's integrated server, so the code path is the same. **Create the
world with cheats allowed**: `/seam` is owners-only and a singleplayer host without cheats is
permission level 0, so the command does not appear at all — indistinguishable from the mod failing
to load. On WSL2 also turn Raw Input off (Options → Controls → Mouse Settings), or the camera whips
around no matter where the sensitivity slider is.

</details>

## 5. Watch it work

```
/seam status
```

It reports what the reporter has actually managed to do, not just that it is switched on: how many
containers are tagged, when the last pass finished, when the last push was accepted and how many
containers it carried, and the last problem if there was one.

The first push leaves at boot, before any sweep, precisely so that a bad token is a line in the log
within a tick instead of a sweep interval later.

From the other side:

```bash
./scripts/seam-tag.sh tags 1      # per container: state, and when it was last seen
./scripts/seam-tag.sh storage 1   # the measured totals per project and item
```

## What "working" looks like

- Every tagged container's `state` goes from `unreadable` to `ok` once its chunk has been loaded.
- `storage` shows counts that match what is in the chests, for the items the project asked for and
  no others.
- Putting more into a chest changes the number within one sweep interval (30s by default).
- Changing **nothing** produces no further pushes. The reporter sends only what changed, so a quiet
  base is quiet — a push per interval carrying the whole world would be a bug.
- Breaking a tagged chest reports it `missing` **once**, and its stock stops counting.
- Walking away until the chunk unloads changes nothing: the last reading stands, because nobody was
  there to change it.

## Tuning

`config/seam-notebook-server.json`, written by `/seam connect`, holds `sweep_seconds` (default 30)
and `reads_per_tick` (default 8) as well as the connection. Both are clamped on load, so a
hand-edited absurdity slows the sweep down rather than stalling the tick loop. Editing the file
needs a restart; `/seam connect` does not.

## Re-running the contract check without Minecraft

`ReporterAgainstRealWebappTest` drives the real reporter against a real mc-org with the block reads
faked. It is skipped unless pointed at one, and it is much faster than launching a client:

```bash
SEAM_SMOKE_BASE_URL=http://localhost:8080 \
SEAM_SMOKE_TOKEN=<reporter token> \
SEAM_SMOKE_WORLD_ID=1 \
./gradlew test --tests '*ReporterAgainstRealWebappTest*'
```
