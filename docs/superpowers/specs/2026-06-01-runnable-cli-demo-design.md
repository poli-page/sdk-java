# Runnable CLI demo — design

**Status**: approved, ready for implementation plan
**Author**: Mickael (with Claude)
**Date**: 2026-06-01

## 1. Purpose

A single CLI entry point that exercises every public endpoint of `page.poli:sdk`
end-to-end against the real develop API. The demo is the manual smoke test we
run before publishing a new SDK version, and the artifact a newcomer runs to
confirm "yes, the SDK works against my account" in under a minute.

It is **not** a release artifact, **not** wired into CI, and **not** unit
tested. The methods it exercises are already covered by `src/test/java/` (unit)
and `src/integrationTest/java/` (gated integration). The demo's job is to
provide a single human-facing pass over the public surface — equivalent to
`sdk-node/demo/node/esm-demo.mjs`, which it mirrors step-for-step.

## 2. Scope

**In scope** — six steps, blocking facade only, mirroring the Node SDK demo:

1. `client.render().pdf(input)` — PDF bytes in memory → `output/render.pdf`.
2. `client.render().pdfStream(input)` — `InputStream` drained to
   `output/stream.pdf`.
3. `client.renderToFile(input, path)` — straight to disk → `output/file.pdf`.
4. `client.render().document(input)` — returns `DocumentDescriptor`.
5. `client.documents().preview(documentId)` — `DocumentPreviewResult` →
   `output/preview.html`.
6. Deliberate `PoliPageException` — sends `version: "banana"`, catches the
   thrown exception, and inspects `code()`, `statusCode()`, `requestId()`,
   `isRetryable()`.

**Out of scope** — explicitly rejected during brainstorm:

- The async facade (`renderAsync`, `documentsAsync`). Sync + async parity is a
  test concern, not a demo concern.
- `documents.get()`, `documents.delete()`, `documents.thumbnails()`. Same
  rationale as Node — the demo mirrors Node's six steps for cross-SDK
  byte-comparability of the printed walkthrough.
- The async facade demo. Sync + async parity is a test concern, not a demo
  concern.

## 3. Layout

```
examples/demo/
├── pom.xml                                  # standalone Maven project, NOT in the parent reactor
├── README.md                                # one screen: prereqs + how to run + troubleshooting
├── .gitignore                               # ignores output/ and target/
└── src/main/java/page/poli/sdk/examples/demo/
    └── Main.java                            # the whole demo, single file

scripts/
└── demo                                     # thin bash wrapper around the Maven invocation
```

The `examples/demo/` Maven project is deliberately **not** listed in the parent
`pom.xml`'s `<modules>` block. Consequences:

- `./mvnw verify` / `./mvnw test` / CI never touch it.
- The demo cannot reference the SDK via reactor build — it depends on the
  SDK's published-to-local-m2 jar instead. That's why the wrapper script runs
  `./mvnw install -DskipTests` first.

## 4. `examples/demo/pom.xml`

A minimal standalone project, ~40 lines.

| Coordinate            | Value                                                  |
| --------------------- | ------------------------------------------------------ |
| `groupId`             | `page.poli`                                            |
| `artifactId`          | `sdk-demo`                                             |
| `version`             | `0.0.1-SNAPSHOT` (never published)                     |
| `packaging`           | `jar`                                                  |
| `maven.compiler.{source,target,release}` | `17`                                |
| `project.build.sourceEncoding` | `UTF-8`                                       |
| Single dependency     | `page.poli:sdk:${sdk.version}`, scope `compile`        |
| `exec-maven-plugin`   | `mainClass = page.poli.sdk.examples.demo.Main`         |

`sdk.version` is a property at the top of the demo pom, set to the current
SDK version. The wrapper script does not pass it on the command line — the
SDK is installed to local m2 first, and Maven resolves it from there.

No Spotless. No tests. No JaCoCo. The demo is reads-once code; CLAUDE.md's
formatting rules don't apply to throwaway example projects.

## 5. `scripts/demo`

Bash, ~10 lines, `set -euo pipefail`.

Responsibilities, in order:

1. `cd "$(dirname "$0")/.."` so the script works from any cwd.
2. `./mvnw -q install -DskipTests` — publishes the working-tree SDK to local
   `~/.m2`. Silent on success; verbose Maven output on failure.
3. `./mvnw -q -f examples/demo/pom.xml exec:java`.

The script does **not** validate `POLI_PAGE_API_KEY` — the Java program
owns that flow (see §6.6). If unset, the program prompts and persists the
key to a repo-root `.env` (mirroring the Node demo's `ensureApiKey`).

The script does **not** check `JAVA_HOME`. If it's unset, `./mvnw` itself
fails with macOS's "Unable to locate a Java Runtime" message — the demo
README's troubleshooting section points at the Homebrew openjdk fix.

The script is `chmod +x` and committed.

## 6. `Main.java`

A single file, package `page.poli.sdk.examples.demo`.

### 6.1 Structure

```java
public final class Main {
  private static final String OUT_DIR = "examples/demo/output";
  private static final int TOTAL_STEPS = 6;
  private static final ProjectModeInput INPUT = ProjectModeInput.builder()
      .project("getting-started")
      .template("welcome")
      .version("1.0.0")
      .data(Map.of("name", "SDK Demo"))
      .build();

  public static void main(String[] args) throws Exception {
    String apiKey = requireEnv("POLI_PAGE_API_KEY");
    String baseUrl = System.getenv().getOrDefault("POLI_PAGE_BASE_URL", "");
    Files.createDirectories(Path.of(OUT_DIR));

    PoliPageClient client = buildClient(apiKey, baseUrl);

    DocumentDescriptor doc = null;
    step1_renderPdf(client);
    step2_renderPdfStream(client);
    step3_renderToFile(client);
    doc = step4_renderDocument(client);
    step5_documentsPreview(client, doc.documentId());
    step6_errorHandling(client);

    Console.println("");
    Console.println(Console.green("✔") + " " + Console.bold("All steps completed.")
        + " Inspect output in " + Console.fileLink(OUT_DIR));
  }

  // … private static step methods, one per public surface call …
}
```

Each `stepN_xxx` method:

- Prints the step header via `Console.step(n, TOTAL_STEPS, label)`.
- Calls one SDK method.
- Writes the result to `output/<name>` where applicable.
- Logs a short result summary (byte count, magic header, document id, page
  count) and an `open: file://…` hint.

### 6.2 Client construction

```java
private static PoliPageClient buildClient(String apiKey, String baseUrl) {
  PoliPageClient.Builder b = PoliPageClient.builder()
      .apiKey(apiKey)
      .onRetry(e -> Console.println(Console.dim(
          "  ↻ retrying after " + e.delayMs() + "ms: " + e.reason())))
      .onError(t -> Console.println(Console.dim(
          "  ✗ error: " + t.getClass().getSimpleName())));
  if (!baseUrl.isEmpty()) {
    b.baseUrl(URI.create(baseUrl));
  }
  return b.build();
}
```

The two registered hooks are the only ones the SDK exposes today (no
`onRequest`/`onResponse` — that's a difference from Node). They print to
stdout via the `Console` helper so output piping stays consistent with the
rest of the demo.

### 6.3 Step 6 — deliberate error

```java
private static void step6_errorHandling(PoliPageClient client) {
  Console.step(6, TOTAL_STEPS, "error handling — DEMO ONLY (we trigger an error on purpose)");
  Console.println(Console.yellow(
      "  ⚠  This step is intentional — the SDK is about to throw, but the"));
  Console.println(Console.yellow(
      "     demo will catch and inspect it. ") + Console.bold("The demo is NOT crashing."));
  Console.println(Console.dim(
      "     (We send an invalid version string, expecting the API to return 400 INVALID_VERSION_FORMAT.)"));

  ProjectModeInput bad = ProjectModeInput.builder()
      .project("getting-started")
      .template("welcome")
      .version("banana")
      .data(Map.of())
      .build();

  try {
    client.render().pdf(bad);
    Console.println("  " + Console.red("✗ unexpected: the call succeeded but should have failed"));
    System.exit(1);
  } catch (PoliPageException e) {
    Console.println("  " + Console.green("✔") + " Error caught successfully. PoliPageException exposed:");
    Console.println("     code:        " + e.code());
    Console.println("     statusCode:  " + e.statusCode());
    Console.println("     requestId:   " + e.requestId());
    Console.println("     isRetryable: " + e.isRetryable());
  }
}
```

If the call **succeeds**, the demo exits non-zero — that's the failure case
the deliberate-error step is designed to surface (it would indicate the API
silently accepted an invalid version or the validation moved server-side).

### 6.4 `Console` helper

Inner class in the same file (or a sibling file, implementer's choice).
Inline ANSI, zero dependencies.

- `boolean ENABLED = System.console() != null;` — disables color when stdout
  is piped to a file or another process.
- `String dim/cyan/green/yellow/red/bold(String s)` — wrap with ANSI codes
  iff `ENABLED`, else return `s` unchanged.
- `void step(int n, int total, String label)` — prints
  `\n[n/total] label` with a single bold line separator above.
- `void println(String s)` — thin `System.out.println` wrapper for
  symmetry with the colored helpers.
- `String fileLink(String path)` — returns `file://<absolute path>` so
  terminals can hyperlink the output. Used for "open:" hints.

### 6.5 Exit codes

| Outcome                                                       | Exit |
| ------------------------------------------------------------- | ---- |
| All six steps complete (step 6 throws + catch hits)           | `0`  |
| Step 1–5 throws (any exception)                               | `1`  |
| Step 6 *succeeds* (the deliberate error didn't fire)          | `1`  |
| Interactive prompt receives a value missing the `pp_test_` prefix | `1`  |

Unexpected exceptions from steps 1–5 bubble out of `main` and are printed
by the JVM with their stack trace — that's the right UX for a demo, since
the trace tells the user exactly where the SDK call failed.

### 6.6 API key & base URL resolution

Mirrors the Node demo's `_shared.mjs` (`ensureApiKey` / `resolveBaseUrl`).
Java-side equivalents are inner classes on `Main`.

**`POLI_PAGE_API_KEY`** — three-tier:

1. Process environment.
2. `.env` at the repo root (resolved as `examples/demo/../../.env`, absolute
   path printed in the "using …" log line).
3. **Interactive prompt** — yellow banner between dim rules, the same
   instructional copy as Node (sign-in URL, `keys` page URL, where the
   key will be saved), a `Paste your pp_test_* key (or Ctrl-C to cancel):`
   prompt read via `BufferedReader.readLine(System.in)`. Validates the
   `pp_test_` prefix → red `✗` + exit `1` if missing. On success appends
   `POLI_PAGE_API_KEY=<value>\n` to `.env` (with a leading newline if the
   file does not already end with one) and prints green `✔ saved to …`.

**`POLI_PAGE_BASE_URL`** — same three tiers, no prompt. Default is
`https://api.poli.page` (matches Node verbatim).

**`EnvFile` helper** — package-private inner class on `Main`:

- `Map<String,String> read(Path)` — line-oriented parser, skips blanks
  and `#` comments, trims, strips a single matching pair of surrounding
  `'` or `"`, last-wins.
- `void append(Path, String key, String value)` — uses
  `Files.writeString(..., CREATE, APPEND)`, prepends `\n` if the file's
  last byte isn't a newline (checked via `FileChannel`, no full read).

**Java-specific concession** — `System.console()` is null under
`mvn exec:java`, so the prompt reads from `System.in` via
`BufferedReader(new InputStreamReader(System.in, UTF_8))`. The pasted
key is visible on screen; Node's `readline.question` doesn't mask it
either, so no parity loss.

## 7. `examples/demo/README.md`

One screen. Sections:

1. **Prereqs** — Java 17+, a `pp_test_*` API key (link to dashboard).
2. **Run** — single command:
   ```bash
   POLI_PAGE_API_KEY=pp_test_... ./scripts/demo
   ```
   Run from the repo root.
3. **What it does** — the six steps listed by name, with the output file
   each one produces.
4. **Troubleshooting** — the macOS "Unable to locate a Java Runtime" case,
   with the `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/...` fix. Also
   the `POLI_PAGE_BASE_URL` env override.

No screenshots, no architecture diagram — the README's audience is "I just
want this to render a PDF and prove the SDK works".

## 8. Testing strategy

**None.** The demo is itself the manual smoke test. The SDK methods it
calls are unit-tested in `src/test/java/` (mocked transport) and
integration-tested in `src/integrationTest/java/` (real develop API). The
demo adds nothing testable on top of those — its job is to render that
coverage visible to a human in <60s.

CLAUDE.md's TDD rule does not apply: the demo is example code under
`examples/`, explicitly excluded from the release artifact, and the rule
is scoped to library code. The implementation plan should call this out
in its preamble so the deviation is intentional rather than accidental.

## 9. Acceptance criteria

The demo is "done" when:

1. `POLI_PAGE_API_KEY=pp_test_... ./scripts/demo` runs to completion with
   exit code `0` against the configured API.
2. `examples/demo/output/` contains four files: `render.pdf`, `stream.pdf`,
   `file.pdf` (all valid PDFs, `%PDF` magic), and `preview.html` (valid
   HTML).
3. The deliberate-error step prints the inspected `PoliPageException`
   fields and the success checkmark.
4. Running `./scripts/demo` with `POLI_PAGE_API_KEY` unset and no `.env`
   shows the prompt, accepts a `pp_test_*` key, appends it to `.env` at
   the repo root, and runs the demo to completion.
5. Running `./scripts/demo` again after step 4 picks the key up from
   `.env` without prompting (logs `using POLI_PAGE_API_KEY from …`).
6. Pasting a value without the `pp_test_` prefix at the prompt exits `1`
   without writing to `.env`.
7. `./mvnw verify` (the main build) is unchanged — the demo module is not
   in the reactor.

## 10. Out of scope (revisit later)

- A `documents-tier` step that exercises `documents.thumbnails()` (gated
  Starter+).
- An `async` variant of the demo running through `renderAsync` /
  `documentsAsync`.
- Cross-SDK byte-diffable output (rendering the same template across all
  twelve SDKs and asserting byte-equality of `render.pdf`). Tracked
  separately in the platform repo.
