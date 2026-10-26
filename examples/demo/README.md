# `page.poli:sdk` — runnable demo

A single CLI program that walks through every public method of the Java SDK
end-to-end against the Poli Page API. The result is a set of files on disk
(three PDFs, the page thumbnails and the stored document's HTML) plus a
deliberate `PoliPageException` caught and inspected at the end.

The demo renders `getting-started`/`welcome` with `data: { name: "SDK Demo" }`
and no version. Every organization gets a `getting-started` project,
published into its `sandbox` environment when the organization is created,
with a `welcome` template, so a `sandbox` key renders it with no setup. It is
a normal, editable project: in an organization that renamed it, deleted it or
removed it from `sandbox`, the API answers `404 PROJECT_NOT_FOUND` or
`404 PROJECT_NOT_IN_ENVIRONMENT`. Use a fresh organization.

## Prereqs

- **Java 17 or later** on your `PATH`.
- A `sandbox` API key (`pp_sandbox_…`; the demo will help you get one on
  first run). Sandbox renders are watermarked, are not counted toward your
  quota, and their documents expire 30 days after creation.

## Run

From the repo root:

```bash
./scripts/demo
```

That's it. The wrapper installs the working-tree SDK to your local `~/.m2/`
first, then runs the demo against it.

On first run, if no `POLI_PAGE_API_KEY` is set, the demo walks you through
creating a sandbox key and saves it to `.env` at the repo root so future
runs pick it up automatically. A pasted key must match the full-key format
shared by every Poli Page SDK, `pp_{env}_{32 lowercase hex}`:

```text
^pp_(?=[a-z][a-z0-9-]{0,19}_)[a-z][a-z0-9]*(?:-[a-z0-9]+)*_[0-9a-f]{32}$
```

To skip the prompt — for CI, or just for a one-off — set the env var inline:

```bash
POLI_PAGE_API_KEY=pp_sandbox_x ./scripts/demo
```

(replace `pp_sandbox_x` with your key).

## What it does

| Step | SDK call                                         | Output                          |
| ---- | ------------------------------------------------ | ------------------------------- |
| 1    | `client.render().pdf(input)`                     | `output/render.pdf`             |
| 2    | `client.render().pdfStream(input)`               | `output/stream.pdf`             |
| 3    | `client.renderToFile(input, path)`               | `output/file.pdf`               |
| 4    | `client.render().document(input)`                | (descriptor in stdout)          |
| 5    | `client.documents().get(documentId)`             | (refreshed URL expiry in stdout)|
| 6    | `client.documents().thumbnails(documentId, …)`   | `output/thumbs/page_N.png`      |
| 7    | `client.documents().preview(documentId)`         | `output/documents_preview.html` |
| 8    | `client.documents().delete(documentId)`          | (stdout)                        |
| 9    | `not-found`: render template `does-not-exist`    | (error in stdout)               |

Step 6 needs the Starter tier or above: on a Free organization the API
answers `403` and the demo prints `skipped` and carries on.

Step 9 renders `{ project: "getting-started", template: "does-not-exist",
data: {} }` on purpose. The API answers `404` with code `TEMPLATE_NOT_FOUND`;
the demo catches the `PoliPageException` and prints its `code`, `statusCode`,
`message` and `requestId`. Any other error stops the demo.

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

**`Could not find artifact page.poli:sdk:0.10.0`** — the wrapper
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
