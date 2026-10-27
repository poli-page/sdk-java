# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.10.0] - 2026-10-26

First release of the Java SDK, on the environments contract: an API key
carries its environment (`pp_{env}_…`, e.g. `pp_sandbox_…`, `pp_live_…`,
`pp_staging_…`) and a render uses the project's copy published in that
environment. No Java artifact was released before; the version follows the
other Poli Page SDKs, whose previous release is 0.9.x. The Removed and
Changed entries describe the difference from pre-release builds of this
repository (`1.0.0-SNAPSHOT`), which spoke the 0.9.x contract. Breaking,
with no deprecation window. See [MIGRATION.md](MIGRATION.md#from-09x-to-0100).

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
- The SDK surface, for Java 17 and later:
  - `PoliPageClient`, built with `PoliPageClient.builder()…build()`.
    Builder options: `apiKey` (required, checked for presence only),
    `baseUrl` (default `https://api.poli.page`), `maxRetries`,
    `retryDelay`, `requestTimeout`, and the `onRequest`, `onResponse`,
    `onRetry` and `onError` hooks.
  - Render facade: blocking `client.render().{pdf,pdfStream,document}`
    and async `client.renderAsync().{pdf,pdfStream,document}` returning
    `CompletableFuture<T>`; `client.renderToFile(input, path)` and
    `renderToFileAsync(...)` stream the PDF to disk.
  - Documents facade: blocking
    `client.documents().{get,preview,thumbnails,delete,downloadPdf}` and
    the async `client.documentsAsync()` counterparts.
  - `RenderInput` record (`project`, `template`, `data`, plus optional
    `metadata`, `format`, `orientation`, `locale` and a caller-chosen
    `idempotencyKey`), built with `RenderInput.builder()`.
  - Exception hierarchy rooted at the sealed
    `PoliPageException extends RuntimeException`, with
    `PoliPageAuthException`, `PoliPageNotFoundException`,
    `PoliPageGoneException`, `PoliPageValidationException`,
    `PoliPageRateLimitException`, `PoliPageNetworkException`,
    `PoliPageDownloadException` and `PoliPageInvalidOptionsException`.
    Each carries `code()`, `statusCode()`, `getMessage()` and
    `requestId()`.
  - `PoliPageErrorCode`: a constant for each of the 31 API codes plus the
    SDK codes (`network_error`, `timeout`, `aborted`, `invalid_options`,
    `download_failed`).
  - Retry loop: exponential backoff with jitter `[0.5, 1.5)`,
    `Retry-After` honoured up to 30s, an `Idempotency-Key` on every POST,
    cancellable via thread interruption (blocking) or
    `CompletableFuture.cancel(true)` (async).
- Runnable demo at `examples/demo` (`./scripts/demo`) exercising every
  public method against the API with a `sandbox` key: it renders
  `getting-started`/`welcome` with no version, checks a pasted key against
  the full-key format, and ends with the `not-found` step
  (`TEMPLATE_NOT_FOUND`, printing `code`, `statusCode`, `message` and
  `requestId`).
- Nightly `Integration` workflow running `./mvnw -B -P integration-tests
  verify` with the `POLI_PAGE_API_KEY` (and optional `POLI_PAGE_BASE_URL`)
  secret; without the key it logs a skip.

### Build & supply chain

- Java 17 floor. CI matrix: Java 17, 21, 25 on Linux; Java 21 on
  Windows and macOS.
- Maven build (`pom.xml`). The artifact is not on Maven Central yet:
  build it from source (see the README's Install section).
- Runtime dependencies: `org.slf4j:slf4j-api` ≥ 2.0,
  `com.fasterxml.jackson.core:jackson-databind` ≥ 2.17.
- `org.jspecify:jspecify` (nullability annotations) is also a
  dependency.
- `release` profile: GPG-signed main, sources and javadoc JARs for
  Maven Central publishing.
