# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.11.0] - 2026-10-30

Conformance with the Poli Page API contract v1.3.0
([poli-page/api-contract](https://github.com/poli-page/api-contract)). See
[MIGRATION.md](MIGRATION.md#from-0100-to-0110).

### Changed

- **BREAKING**: `QUOTA_EXCEEDED` and `OVERAGE_CAP_EXCEEDED` (429) and
  `STORAGE_ACCESS_DENIED` (502) are no longer retried, even with a
  `Retry-After` header, and `isRetryable()` now returns `false` for them (it
  returned `true` for any 429 or 5xx). They last until the monthly reset or
  until the bucket access is restored. `RATE_LIMIT_EXCEEDED` and every other
  429 and 5xx are still retried. The retry loop and `isRetryable()` now
  follow one rule.
- **BREAKING**: `isRetryable()` is `false` for `DOWNLOAD_FAILED` whatever the
  storage status (it was `true` for a storage 5xx or 429), and for `aborted`.
  The presigned-URL download is never retried.
- **BREAKING**: `PoliPageErrorCode.DOWNLOAD_FAILED` is `"DOWNLOAD_FAILED"`
  (was `"download_failed"`), the SDK specification's code. It is a
  compile-time constant: code compiled against 0.10.0 has `"download_failed"`
  inlined, so recompile against the new version. Code that compares with the
  literal string must change.
- **BREAKING**: SDK error messages are the SDK specification's exact texts:
  - `network_error`: `Network request failed` (was `<METHOD> <path> failed:
    <JDK message>`);
  - `timeout`: `Request timed out after <ms>ms`, the configured
    `requestTimeout` (was `<METHOD> <path> timed out: <JDK message>`);
  - `aborted`: `Request was aborted` (was `<METHOD> <path> was interrupted`);
  - `invalid_options` for a missing or blank key: `apiKey is required`;
  - `DOWNLOAD_FAILED`: `Failed to download PDF: HTTP <status>` (was `Failed
    to download PDF from presigned URL: HTTP <status>`), and `Failed to
    download PDF: network error` when the download gets no answer.
  The JDK exception stays available as `getCause()`.
- **BREAKING**: A network failure or a timeout of the presigned-URL download
  (`render().pdf`, `pdfStream`, `downloadPdf`) is `DOWNLOAD_FAILED` with
  status `0` (was `network_error` or `timeout` from `render().pdf`, and
  `timeout` from `downloadPdf`).
- **BREAKING**: `RetryEvent.attempt()` is the number of the attempt about to
  start, 1-based like `RequestEvent.attempt()`: `2` for the first retry (was
  the 0-based index of the attempt that failed, `0`).
- **BREAKING**: `onResponse` fires on 2xx answers only, once the body is read,
  and `durationMs` covers the body (it fired on every answer, error statuses
  included, before the body was read).
- **BREAKING**: `onError` fires once with every error a call ends with: API
  errors, `network_error`, `timeout`, `aborted` (also for a cancelled async
  future), `DOWNLOAD_FAILED`, `invalid_response`, `io_error` and any other
  exception the call throws (it fired only on exhausted transport failures).
- A `Retry-After` header must be digits (delta-seconds) or an HTTP-date. A
  negative or fractional value (`-1`, `1.5`) is now ignored and the backoff
  applies (a negative value meant "retry at once"); a value too large for a
  `long` clamps to the 30 s cap.
- `requestTimeout` bounds each attempt as a whole: connecting, the headers and
  the body. The JDK's request timeout stops once the headers arrive, so a body
  that kept dripping could outlive it; it now fails the attempt with
  `timeout`, retried. It also bounds the presigned-URL download of
  `render().pdf` and `downloadPdf`.

### Fixed

- No hidden resends: `java.net.http` sends a `GET` again by itself (once up
  to JDK 21, up to 4 more times on later JDKs) when the connection closes
  before any answer, and any method, `POST /v1/render` included, when the JVM
  runs with `-Djdk.httpclient.enableAllMethodRetry=true`. The SDK now refuses
  those resends: the failure is a `network_error` that the SDK retries itself,
  counted by `maxRetries`, reported to `onRequest` and `onRetry`, and the
  presigned-URL download is sent once. Up to JDK 21 a `GET` and a `DELETE` now
  carry `Content-Length: 0`, as `HttpRequest.Builder.GET()` did up to JDK 18.
- The body of every API answer is read inside its attempt. A 2xx body cut
  mid-way is a `network_error` and a stalled one a `timeout`, both retried; an
  error answer whose body is cut or stalls keeps its status and request id
  (`INTERNAL_ERROR`, `HTTP <status>`, the read failure as `getCause()`) and is
  retried according to its status. A render whose 2xx answer is cut was
  already stored by the API; its retry can store a second document.
- A dropped connection while a `pdfStream()` body is read raises
  `DOWNLOAD_FAILED` (unchecked) from `read()`, not a raw `IOException`;
  `renderToFile` therefore throws `DOWNLOAD_FAILED` rather than `io_error`
  in that case.
- **BREAKING**: Cancelling a future of `renderAsync()`, `documentsAsync()` or
  `renderToFileAsync()` aborts the call: the attempt in flight, the wait
  before a retry or the download is cancelled and no further request is sent
  (it used to cancel only the returned future and let the retries and the
  download go on). `get()` / `join()` still throw `CancellationException`,
  now with the SDK's `aborted` error (`Request was aborted`) as its cause, and
  `onError` fires once with it (in a rare race, where an inner step fails at
  the same moment as the cancel, one extra `onError` can fire). A PDF
  stream that arrives after the cancel is closed; a cancel during
  `renderToFileAsync`'s copy closes the stream and stops the copy.
- `renderToFile` and `renderToFileAsync` write the PDF to a temporary file
  next to the target, then move it onto the target (atomically where the
  file system can). A call that fails or is cancelled never leaves a partial
  file: the target is either as it was or replaced by the complete PDF (a
  cancel that lands once the download is complete may still replace it), and
  the temporary file is removed. (They used to delete the target first, then
  write into it.) An `io_error` names the target path; the temporary file is
  in its cause.
- The JDK client's fixed 10 s connect timeout is gone: `requestTimeout`
  bounds the connect like the rest of the attempt. A connect that hung used to
  fail after 10 s with `Request timed out after <requestTimeout>ms`.
- A retry wait is never shorter than the delay: the blocking sleep and the
  async timer wait again for the rest when they wake early.
- An error answer's `Retry-After` larger than a `long` no longer falls back to
  the short backoff.
- Docs: the API does not deduplicate on `Idempotency-Key`. A render retried
  after a late 5xx, a `timeout` or a `network_error` can store a second
  document; the retries page and the README no longer say otherwise.

### Added

- Known error code `TEMPLATE_UNSAFE` (400: the template, a partial or an SVG
  asset holds an injection attempt; its message names it; not retried).
- An explicit `serialVersionUID` on every exception class. Exceptions
  serialized by 0.10.0, whose UID was computed, no longer deserialize.
- Exception constructors that take a cause: `PoliPageAuthException`,
  `PoliPageValidationException`, `PoliPageNotFoundException`,
  `PoliPageGoneException` and `PoliPageRateLimitException`.
- The API contract tests: `ContractTest` runs every SDK case of the API
  contract against a local mock server, on the blocking and the async facade.
  CI pins the contract by commit (`poli-page/api-contract` action) and
  Dependabot proposes each new version. Run it locally with
  `POLI_CONTRACT=<path to contract.json> ./mvnw test`.

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
