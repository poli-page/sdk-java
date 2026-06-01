# `page.poli:sdk` — runnable demo

A single CLI script that walks through every public method of the Java SDK
end-to-end against the develop API. The result is four files on disk
(`render.pdf`, `stream.pdf`, `file.pdf`, `preview.html`) plus a deliberate
`PoliPageException` caught and inspected at the end.

The demo renders the `getting-started/welcome/1.0.0` template, which is
auto-provisioned in every Poli Page org — so it works out of the box with any
fresh API key, no project setup needed.

## Prereqs

- **Java 17 or later** on your `PATH`.
- A `pp_test_*` API key (the demo will help you get one on first run).

## Run

From the repo root:

```bash
./scripts/demo
```

That's it. The wrapper installs the working-tree SDK to your local `~/.m2/`
first, then runs the demo against it.

On first run, if no `POLI_PAGE_API_KEY` is set, the demo walks you through
creating a test key and pastes it into `.env` at the repo root so future
runs pick it up automatically. To skip the prompt — for CI, or just for a
one-off — set the env var inline:

```bash
POLI_PAGE_API_KEY=pp_test_... ./scripts/demo
```

## What it does

| Step | SDK call                                                   | Output                |
| ---- | ---------------------------------------------------------- | --------------------- |
| 1    | `client.render().pdf(input)`                               | `output/render.pdf`   |
| 2    | `client.render().pdfStream(input)`                         | `output/stream.pdf`   |
| 3    | `client.renderToFile(input, path)`                         | `output/file.pdf`     |
| 4    | `client.render().document(input)`                          | (descriptor in stdout)|
| 5    | `client.documents().preview(documentId)`                   | `output/preview.html` |
| 6    | Send `version: "banana"` → catch `PoliPageException`       | (inspected in stdout) |

Outputs land in `examples/demo/output/` (gitignored). Open the PDFs and
HTML to confirm the SDK is producing valid documents end-to-end.

## Configuration

The demo resolves `POLI_PAGE_API_KEY` and `POLI_PAGE_BASE_URL` from three
sources, in order:

1. **Process environment** — wins over everything. Best for CI.
2. **`.env` at the repo root** — `KEY=value` lines, `#` comments allowed.
   The interactive prompt writes here on first run.
3. **Default** — `POLI_PAGE_BASE_URL` falls back to `https://api.poli.page`.
   `POLI_PAGE_API_KEY` has no default; the demo prompts when none is found.

Setting `NO_COLOR=1` disables ANSI colors in the output.

## Troubleshooting

**`Unable to locate a Java Runtime`** on macOS — `java` resolves to the
Apple stub, not a real JDK. If you installed openjdk via Homebrew:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
```

Add those lines to `~/.zshrc` to make it stick across shells.

**`Could not find artifact page.poli:sdk:1.0.0-SNAPSHOT`** — the wrapper
runs `mvn install -DskipTests` first to publish the working-tree SDK to
your local `~/.m2/`. If that step failed, scroll up in the output to find
the underlying error (usually a compile or formatting issue in the SDK
itself).

## Not a release artifact

`examples/demo/` is a standalone Maven project, deliberately kept out of
the parent reactor. It is not built or tested by `./mvnw verify` or CI, and
it is not published to Maven Central. The SDK methods it exercises are
covered by unit tests in `src/test/java/` and integration tests in
`src/integrationTest/java/`; the demo's only job is to make that coverage
visible to a human in under a minute.
