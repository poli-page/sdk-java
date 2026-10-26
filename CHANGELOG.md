# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Environments contract: an API key carries its environment (`pp_{env}_…`,
e.g. `pp_sandbox_…`, `pp_live_…`, `pp_staging_…`) and a render uses the
project's copy published in that environment. Breaking, with no
deprecation window.

### Removed

- **BREAKING**: `render().preview` / `renderAsync().preview` and the
  `PreviewResult` type. The API removed `POST /v1/render/preview` (a call
  now gets `404`). `documents().preview(id)` stays.
- **BREAKING**: Inline mode: `InlineModeInput` and the sealed
  `RenderInput` interface.
- **BREAKING**: The `version` component and builder method of the render
  input, and with them draft rendering by an API key.
- **BREAKING**: The `version` component of `DocumentDescriptor`.
- **BREAKING**: `PoliPagePaymentRequiredException` (no route the SDK calls
  answers `402`; payment grace is `403 ORGANIZATION_PAYMENT_GRACE`).
- **BREAKING**: From `PoliPageErrorCode`: `VERSION_NOT_FOUND`,
  `INVALID_VERSION_FORMAT`, `VERSION_REQUIRED`,
  `INVALID_VERSION_FOR_KEY_ENV`, `MISSING_PROJECT_OR_TEMPLATE`, `GONE` (the
  API answers `410 DOCUMENT_GONE`) and `PAYMENT_REQUIRED`.

### Changed

- **BREAKING**: `ProjectModeInput` is renamed `RenderInput` (a record) and
  is the only render input. The request body holds only `project`,
  `template`, `data`, `metadata`, `format`, `orientation` and `locale`;
  unset optional fields are omitted. The SDK never sends an environment:
  the API resolves it from the key.
- **BREAKING**: Error codes come from the API's body: `code`, else
  `error`, else `unknown_error`; a body that is not JSON gives
  `INTERNAL_ERROR` with the real status. The SDK no longer infers a code
  from the HTTP status, so a `404 TEMPLATE_NOT_FOUND` surfaces as
  `TEMPLATE_NOT_FOUND` (not `NOT_FOUND`) and unknown codes pass through
  verbatim.
- `DocumentDescriptor.environment()` is any environment name the API
  returns (`sandbox`, `live`, `staging`, …), unchanged.
- **BREAKING**: Every `ThumbnailOptions` component is optional (`width` is
  an `Integer`), the builder no longer defaults `format` to `PNG`, and the
  SDK no longer checks `width`, `quality` or `pages` client-side: the API
  answers `400 VALIDATION_ERROR`. An unset option is not sent; with none
  set the body is `{}` (API defaults: 400 px wide `jpeg`).
- `documents().delete(id)` is documented as idempotent: the API answers
  `204` for an already-deleted document too.

### Added

- `documents().thumbnails(id)` and `documentsAsync().thumbnails(id)`
  without options.
- `ThumbnailFormat.WEBP`.
- `PoliPageErrorCode`: `PUBLISH_IN_PROGRESS` (503, retried like any 5xx,
  honouring `Retry-After`), `ENVIRONMENT_MISMATCH` (400),
  `PROJECT_NOT_IN_ENVIRONMENT` (404), `TEMPLATE_NOT_FOUND` (404),
  `DOCUMENT_GONE` (410), `ENVIRONMENT_PROTECTED` (403),
  `ORGANIZATION_PAYMENT_GRACE` (403), `EMPTY_DOCUMENT` (400),
  `STORAGE_ACCESS_DENIED` (502), `ORGANIZATION_MIGRATING` (503),
  `PROJECT_NOT_FOUND` (404), `TEMPLATE_ERROR` (400),
  `THUMBNAILS_NOT_AVAILABLE` (403), `RATE_LIMIT_EXCEEDED` (429),
  `RENDER_BODY_TOO_LARGE` (413), `DOCUMENT_TOO_LARGE` (413),
  `RENDER_TIMEOUT` (504), and the kept API code
  `PROJECT_REQUIRED_FOR_DOCUMENT` that was missing.

### Initial scaffolding

Initial scaffolding for the Poli Page Java SDK. Targets Java 17 LTS as
the floor; CI matrix Java 17, 21, 24. Behaviour parity with
`@poli-page/sdk@1.0` (see [MIGRATION.md](MIGRATION.md#10) for the
parity checklist) is the goal for the first `1.0.0` release.

### Planned

- `PoliPageClient` constructed via `PoliPageClient.builder()…build()`.
- Builder options: `apiKey`, `baseUrl`, `maxRetries`, `retryDelay`,
  `requestTimeout`, `httpClient`, `logger`, `onRetry`, `onError`.
- Per-call `RequestOptions` builder with `idempotencyKey`,
  `requestTimeout`, `header(name, value)`.
- Render facade: blocking `client.render().{pdf,pdfStream,document}`
  and async `client.renderAsync().{pdf,pdfStream,document}`
  returning `CompletableFuture<T>`.
- Documents facade: blocking `client.documents().{get,preview,thumbnails,delete}`
  and async `client.documentsAsync().{get,preview,thumbnails,delete}`.
- `DocumentDescriptor.downloadPdf()` and `downloadPdfAsync()` using the
  parent client's `java.net.http.HttpClient` (no auth, no retry).
- `PoliPageClient.renderToFile(input, path)` and `renderToFileAsync(...)`
  — stream the PDF to disk via `pdfStream` + `Files.copy`.
- `RenderInput` record (project, template, data, plus optional metadata,
  format, orientation, locale) built via `RenderInput.builder()`.
- Exception hierarchy rooted at `PoliPageException extends RuntimeException`
  with subclasses `PoliPageAuthException`, `PoliPageNotFoundException`,
  `PoliPageGoneException`, `PoliPageValidationException`,
  `PoliPageRateLimitException`, `PoliPageNetworkException`,
  `PoliPageDownloadException`. Each carries
  `code()`, `statusCode()`, `getMessage()`, `requestId()`.
- `PoliPageErrorCode` static class with a constant for each of the 31
  API codes plus the SDK codes (`network_error`, `timeout`, `aborted`,
  `invalid_options`, `download_failed`).
- Retry loop: exponential backoff with jitter `[0.5, 1.5)`,
  `Retry-After` honoured up to 30s, cancellable mid-flight via thread
  interruption (blocking) or `CompletableFuture.cancel(true)` (async).
- SLF4J integration via builder's `.logger(...)` — DEBUG/attempt,
  WARN/retry, ERROR/terminal. `Authorization` header never logged.
- OpenTelemetry instrumentation under the source name `page.poli.sdk`.
- Runnable demo at `examples/demo` exercising every public method
  against the real API. First-run prompts for a `pp_sandbox_*` key and
  persists to `.env`; subsequent runs are silent.

### Build & supply chain

- Java 17 floor. CI matrix: Java 17, 21, 24 on Linux; Java 21 on
  Windows and macOS. Tracks Java's LTS support cadence (latest two
  LTS plus current).
- Maven build (`pom.xml`); Gradle consumers use the published artifact
  via the standard `implementation("page.poli:sdk:…")` declaration.
- Runtime dependencies: `org.slf4j:slf4j-api` ≥ 2.0,
  `com.fasterxml.jackson.core:jackson-databind` ≥ 2.17.
- Build-time dependencies: `org.jspecify:jspecify` (nullability),
  `com.google.errorprone:error_prone_core` (compile-time checks).
- JAR signing via GPG for OSSRH / Maven Central publishing.
- Triple-JAR publishing: compiled classes + sources + javadoc.
