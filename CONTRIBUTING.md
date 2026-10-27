# Contributing to `page.poli:sdk` (sdk-java)

Thanks for your interest. A few short rules:

## Working method

We use **TDD**: write a failing test first, then the minimum code to
pass. Each public method has a corresponding test in
`src/test/java/`. See `CLAUDE.md` for the full methodology.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/):
`feat:`, `fix:`, `docs:`, `refactor:`, `test:`, `chore:`.

## Local development

```bash
./mvnw verify                          # compile + format check + unit tests
./mvnw spotless:check                  # format only
./mvnw test                            # unit tests only
./mvnw javadoc:javadoc                 # generate Javadoc into target/site/apidocs/
```

We ship the Maven Wrapper (`./mvnw`) so contributors don't need a
system-wide Maven install. CI fails on any format or test violation.

Optional: Spotless can auto-format with `./mvnw spotless:apply`.

## Integration tests

Integration tests hit the API. They live in a separate Maven profile
and are gated by the `POLI_PAGE_API_KEY` env var:

```bash
export POLI_PAGE_API_KEY=pp_sandbox_...
./mvnw verify -P integration-tests
```

To skip integration tests on push (the default), simply run without
`-P integration-tests` — the profile is not active by default.

## Releasing

Releases are **tag-driven** and publish to Maven Central through the
Sonatype Central Portal ([`release.yml`](.github/workflows/release.yml)).
A tag alone never publishes: the publish job waits for a maintainer to
approve it on the `maven-central-release` environment. Central releases
are immutable — a version can never be deleted or replaced.

1. Bump `<version>` in `pom.xml`.
2. Move `[Unreleased]` to `[X.Y.Z] - YYYY-MM-DD` in `CHANGELOG.md`.
3. If a MAJOR bump, add a section to `MIGRATION.md`.
4. Merge `chore(release): vX.Y.Z` to `main` and wait for CI to go green.
5. Optional local dry run (needs the release GPG key): build the signed
   bundle without uploading anything:
   ```bash
   ./mvnw -B -P release -Dcentral.skipUpload=true deploy
   ```
   The bundle is written to `target/central-publishing/central-bundle.zip`.
   Add `-Djapicmp.skip=true` only if nothing is on Central yet.
6. Tag the merge commit and push the tag:
   `git tag vX.Y.Z <sha> && git push origin vX.Y.Z`.
7. The `verify` job checks that the tag matches `pom.xml`, runs the
   full build and the unsigned release-profile build (sources + javadoc
   JARs, japicmp against the previous release on Central).
8. Approve the `publish` job in the Actions UI. It signs, uploads to the
   Central Portal, auto-publishes, waits until the deployment is
   PUBLISHED, then creates the GitHub Release from the `CHANGELOG.md`
   section.
9. Check `https://repo1.maven.org/maven2/page/poli/sdk/X.Y.Z/` (Central
   sync can take 10–30 minutes after PUBLISHED).

The Central user token and the GPG key live as environment secrets on
`maven-central-release` (`CENTRAL_USERNAME`, `CENTRAL_PASSWORD`,
`GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`). The signing key is
`2DBB20D4D79D527DE02B8C3635C61308FA37BB15`, published on
keys.openpgp.org.

### Stable vs. prerelease channels

Maven Central treats any semver with a non-numeric suffix as a
prerelease (`1.0.0-RC1`, `2.0.0-beta1`). Build tools resolve only
stable versions by default; users opt in with explicit version pins.

#### Cutting a prerelease

1. Bump `<version>` to e.g. `2.0.0-RC1`. Maven's convention is
   uppercase suffix without a dot (`RC1`, not `rc.1`) — keep this for
   compatibility with `versions-maven-plugin`.
2. Move `[Unreleased]` → `[2.0.0-RC1] - YYYY-MM-DD` in `CHANGELOG.md`.
3. Commit `chore(release): v2.0.0-RC1`.
4. Tag `v2.0.0-RC1` and approve the publish job, as above. The workflow
   marks the GitHub Release as a prerelease.

Users opt in:

```xml
<dependency>
    <groupId>page.poli</groupId>
    <artifactId>sdk</artifactId>
    <version>2.0.0-RC1</version>
</dependency>
```

#### Promoting a prerelease to stable

When the prerelease is ready, cut a stable release at the same semver
minus the suffix:

1. Bump `<version>` to `2.0.0` (drop the suffix).
2. Move the prerelease entries in `CHANGELOG.md` under
   `[2.0.0] - YYYY-MM-DD`.
3. Merge, tag `v2.0.0`, approve the publish job.

Stable and prerelease versions must never share a tag — once a
prerelease is promoted, the next prerelease starts a new pre-suffix
sequence (e.g. `2.1.0-beta1`).
