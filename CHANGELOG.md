# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- Render facade: blocking `client.render().{pdf,pdfStream,preview,document}`
  and async `client.renderAsync().{pdf,pdfStream,preview,document}`
  returning `CompletableFuture<T>`.
- Documents facade: blocking `client.documents().{get,preview,thumbnails,delete}`
  and async `client.documentsAsync().{get,preview,thumbnails,delete}`.
- `DocumentDescriptor.downloadPdf()` and `downloadPdfAsync()` using the
  parent client's `java.net.http.HttpClient` (no auth, no retry).
- `PoliPageClient.renderToFile(input, path)` and `renderToFileAsync(...)`
  — stream the PDF to disk via `pdfStream` + `Files.copy`.
- Sealed `RenderInput` abstract base permitting `ProjectModeInput` and
  `InlineModeInput` only — `render().pdf` / `pdfStream` / `document`
  enforce project-mode-only at compile time.
- Exception hierarchy rooted at `PoliPageException extends RuntimeException`
  with subclasses `PoliPageAuthException`, `PoliPageNotFoundException`,
  `PoliPageGoneException`, `PoliPageValidationException`,
  `PoliPageRateLimitException`, `PoliPagePaymentRequiredException`,
  `PoliPageNetworkException`, `PoliPageDownloadException`. Each carries
  `code()`, `statusCode()`, `getMessage()`, `requestId()`.
- `PoliPageErrorCode` static class with code constants matching the
  spec (`UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `VERSION_NOT_FOUND`,
  `DOCUMENT_NOT_FOUND`, `GONE`, `VALIDATION`, `RATE_LIMIT`, `TIMEOUT`,
  `ABORTED`, `NETWORK`, `DOWNLOAD_FAILED`, `PAYMENT_REQUIRED`,
  `ORGANIZATION_CANCELLED`, `ORGANIZATION_PURGED`, `QUOTA_EXCEEDED`,
  `OVERAGE_CAP_EXCEEDED`, `INVALID_VERSION_FORMAT`, `VERSION_REQUIRED`,
  `INVALID_VERSION_FOR_KEY_ENV`, `STORAGE_REQUIRED`).
- Retry loop: exponential backoff with jitter `[0.5, 1.5)`,
  `Retry-After` honoured up to 30s, cancellable mid-flight via thread
  interruption (blocking) or `CompletableFuture.cancel(true)` (async).
- SLF4J integration via builder's `.logger(...)` — DEBUG/attempt,
  WARN/retry, ERROR/terminal. `Authorization` header never logged.
- OpenTelemetry instrumentation under the source name `page.poli.sdk`.
- Runnable demo at `examples/demo` exercising every public method
  against the real API. First-run prompts for `pp_test_*` key and
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
