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
export POLI_PAGE_API_KEY=pp_test_...
export POLI_PAGE_BASE_URL=https://api-develop.poli.page   # optional
./mvnw verify -P integration-tests
```

To skip integration tests on push (the default), simply run without
`-P integration-tests` — the profile is not active by default.

## Releasing

Releases are **manual**. There is no CI workflow that auto-publishes —
by design. The Maven Central publish step requires GPG signing of the
artifact, and we keep the human gate where the maintainer reviews the
staged release before it ships.

1. Bump `<version>` in `pom.xml`.
2. Move `[Unreleased]` to `[X.Y.Z] - YYYY-MM-DD` in `CHANGELOG.md`.
3. If a MAJOR bump, add a section to `MIGRATION.md`.
4. Commit `chore(release): vX.Y.Z` on `main`.
5. Run the pre-flight verification locally:
   ```bash
   ./mvnw clean verify -P release
   ```
   The `release` profile runs the full build, generates the sources +
   javadoc JARs, signs all three with GPG, and stages them under
   `target/staging-deploy/`.
6. Inspect the staged artifacts (`ls -la target/staging-deploy/`).
7. Tag locally: `git tag vX.Y.Z`.
8. Push the tag when ready: `git push origin vX.Y.Z`.
9. Push to OSSRH staging: `./mvnw deploy -P release`.
10. Log in to <https://central.sonatype.com>, verify the staged
    repository, and close + release it. This is the irreversible step;
    do it from a clean machine state.
11. Optionally create a GitHub Release from the tag for the changelog
    excerpt — `gh release create vX.Y.Z --notes-from-tag`.

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
4. Run the pre-flight + sign + deploy steps above. OSSRH routes the
   prerelease alongside the stable release.

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
3. Commit, tag, sign, deploy.

Stable and prerelease versions must never share a tag — once a
prerelease is promoted, the next prerelease starts a new pre-suffix
sequence (e.g. `2.1.0-beta1`).
