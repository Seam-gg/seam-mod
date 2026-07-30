---
name: release
description: Cut a Seam Notebook release — bump the version, write the changelog, tag and push. Use when the user asks to release, ship, publish, or cut a version of seam-mod, or to prepare release notes for it.
---

# Release Seam Notebook

Produces the annotated tag that `.github/workflows/release.yml` turns into a Modrinth version.
The tag message **is** the changelog — it is rendered verbatim on the Modrinth version page and in
the GitHub release. Write it for players, not for the repo.

Publishing still waits on a human: the workflow's `publish` job targets the `modrinth`
environment, which requires an approval click. Pushing the tag does not ship anything by itself.

## Version scheme

`<major>.<minor>.<patch>+<minecraft_version>` — e.g. `0.2.0+1.21.11`.

Only the semver half is hand-edited, as `modVersion` in `gradle.properties`. The `+mc` half is
derived in `build.gradle.kts` from `minecraft_version`. Never type the full string into a file;
read it back with `./gradlew -q printVersion`.

Choosing the bump, while pre-1.0:

- **patch** — fixes and internal changes only, nothing new a player would notice
- **minor** — any new capability, or a change to what the notebook shows or does
- **major** — stays `0` until a shipped jar is expected to keep working against a redeployed
  webapp. The mod and mc-org's `ApiDtos.kt` still change together, so that promise doesn't hold yet.

An MC-only rebuild (no code change, new `minecraft_version`) keeps `modVersion` and gets a new
version string from the suffix alone. Do not invent a patch bump for it.

## Steps

### 1. Preflight

Stop and report rather than fixing these silently:

```bash
git status --porcelain          # must be empty
git rev-parse --abbrev-ref HEAD # must be main
git fetch origin && git status -sb | head -1   # must not be behind origin/main
./gradlew build                 # must pass before anything is tagged
```

### 2. Gather what changed

```bash
git describe --tags --abbrev=0        # previous release tag (fails if this is the first)
git log <previous-tag>..HEAD --oneline
```

For the first release, use the full history: `git log --oneline`.

Read the actual diffs where a commit subject doesn't make the player-visible effect obvious.
Internal commit subjects (`MCO-269: push resource sync, task toggles, and an offline queue`) are
raw material, not changelog lines.

### 3. Bump and commit

Edit `modVersion` in `gradle.properties`, then:

```bash
git commit -am "Release <new-version>"
```

Confirm the composed version: `./gradlew -q printVersion`.

### 4. Write the tag message

Show the user the draft and get agreement before tagging — a pushed tag is awkward to correct.

Format:

```
Seam Notebook <version> for Minecraft <mc-version>

### Added
- Gather mode: items you pick up now count toward project resources automatically.

### Changed
- The notebook opens on the project you last had open.

### Fixed
- The notebook no longer swallows the N key while a sign edit screen is open.
```

Rules:

- Title line is plain text — the sections below it are markdown, rendered on the Modrinth page.
- Keep a Changelog headings only: `### Added`, `### Changed`, `### Fixed`, `### Removed`. Omit any
  section with nothing in it.
- Every bullet describes something a player can observe. Say what is different for them, not what
  moved in the code.
- No Linear IDs, no file or class names, no commit hashes, no "refactored X", no test-only changes.
- If a release genuinely contains nothing player-visible, say so in one line rather than padding it.
- Lead each section with the change a player is most likely to care about.

### 5. Tag and push

```bash
git tag -a --cleanup=whitespace "v<version>" -m "<message>"
git push origin main
git push origin "v<version>"
```

**`--cleanup=whitespace` is not optional.** `git tag -a` defaults to `--cleanup=strip`, which
treats every line beginning with `#` as a comment and deletes it — silently removing all four
`### ` headings from the message. Nothing warns you; the tag simply ends up with a bare list of
bullets. Always verify after tagging:

```bash
git for-each-ref "refs/tags/v<version>" --format='%(contents)'
```

The headings must still be there. (`+` in the tag name is fine — it is legal in a git ref.)

Then tell the user: the build runs immediately, and the Modrinth upload waits for their approval
in the repository's Actions tab.

## When it goes wrong

- **Tag doesn't match the built version** — the workflow fails the build on purpose. Delete the
  tag (`git push origin :refs/tags/<tag>` and `git tag -d <tag>`), fix `gradle.properties`, retag.
  Safe as long as the release was never approved.
- **Bad changelog, already published** — do not retag. Both the GitHub release body and the
  Modrinth changelog stay editable after the fact; fix them in the web UI.
- **Unannotated tag** — the workflow refuses it, since there is no message to publish. Retag with
  `git tag -a`.
