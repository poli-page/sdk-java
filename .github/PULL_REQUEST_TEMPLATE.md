<!--
Title format: Conventional Commits — feat: …, fix: …, docs: …, refactor: …, test: …, chore: …
One concern per PR. A reviewer should be able to land it in under 30 minutes.
-->

## What

<!-- One paragraph: what changed and why. Link issues with "Closes #123" if applicable. -->

## Why

<!-- The motivation. A breaking change, a bug, a missing capability, a follow-up to a prior PR. -->

## How it was tested

- [ ] `./mvnw spotless:check` is clean
- [ ] `./mvnw verify` is green (unit tests + JaCoCo coverage gate)
- [ ] Integration tests run (`./mvnw -P integration-tests verify`) — strike out if not relevant
- [ ] Public API surface unchanged, OR a MINOR/MAJOR version bump is justified in the description
- [ ] `CHANGELOG.md` updated under `[Unreleased]`
- [ ] `MIGRATION.md` updated (MAJOR only)

## Follow-ups deliberately deferred

<!-- List anything you knowingly left out of this PR, with a short rationale. -->
