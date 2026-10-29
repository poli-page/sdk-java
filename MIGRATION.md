# Migration Guide

This file documents breaking changes between major versions of
`page.poli:sdk`. We follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html):
breaking changes ship in major version bumps (in minor bumps while the
version is `0.x`) and always come with an entry here.

## From 0.10.0 to the next release

The SDK now follows the Poli Page API contract (SDK specification §8, §9).

### 1. Three codes are never retried

`QUOTA_EXCEEDED`, `OVERAGE_CAP_EXCEEDED` (429) and `STORAGE_ACCESS_DENIED`
(502) are thrown at once, and `isRetryable()` is `false` for them, for
`aborted` and for `DOWNLOAD_FAILED`. A job queue that requeues on
`isRetryable()` stops requeueing them: they last until the monthly reset or
until the bucket access is restored, so surface them instead.

### 2. `DOWNLOAD_FAILED` is uppercase

```java
// before
if ("download_failed".equals(ex.code())) { … }
// after: use the constant
if (PoliPageErrorCode.DOWNLOAD_FAILED.equals(ex.code())) { … }
```

A download that gets no answer, or exceeds `requestTimeout`, is now
`DOWNLOAD_FAILED` with `statusCode() == 0`, not `network_error` or
`timeout`. A connection dropped while a `pdfStream()` body is read throws
`DOWNLOAD_FAILED` from `read()`.

### 3. Messages are fixed texts

Code that parsed `getMessage()` of an SDK error sees `Network request
failed`, `Request timed out after <ms>ms`, `Request was aborted`, `apiKey is
required`, `Failed to download PDF: HTTP <status>` and `Failed to download
PDF: network error`. The JDK exception is `getCause()`.

### 4. Hooks

- `RetryEvent.attempt()` is the attempt about to start: `2` before the first
  retry (was `0`). Add 2 to an old value to get the new one.
- `onResponse` fires on 2xx answers only, after the body.
- `onError` fires with every error a call throws, API errors included.

## From 0.9.x to 0.10.0

`0.10.0` is the first release of the Java SDK. No `0.9.x` Java artifact
was ever published; this entry is for code written against a pre-release
build of this repository (`1.0.0-SNAPSHOT`, which spoke the 0.9.x
contract) or ported from another Poli Page SDK at 0.9.x.

The API moved to environments: an API key belongs to one environment and
a render uses the project's copy published in that environment. Every
removal ships at once, with no deprecation window.

### 1. API keys name their environment

A key is `pp_{env}_{32 lowercase hex}` (`pp_sandbox_…`, `pp_live_…`,
`pp_staging_…`), matching

```text
^pp_(?=[a-z][a-z0-9-]{0,19}_)[a-z][a-z0-9]*(?:-[a-z0-9]+)*_[0-9a-f]{32}$
```

`pp_test_…` and `pp_sa_…` keys are gone: create a key for the environment
you render from (a `sandbox` key replaces a test key). Standard and
service keys share the one format. The SDK never sends an environment.

### 2. `ProjectModeInput` is `RenderInput`, with no `version`

```java
// Before
ProjectModeInput input = ProjectModeInput.builder()
    .project("billing")
    .template("invoice")
    .version("1.0.0")
    .data(Map.of("invoiceNumber", "INV-001"))
    .build();

// After
RenderInput input = RenderInput.builder()
    .project("billing")
    .template("invoice")
    .data(Map.of("invoiceNumber", "INV-001"))
    .build();
```

There is no version to pick and no key renders the draft: publish the
project to the key's environment. `DocumentDescriptor.version()` is gone
too.

### 3. No `render().preview`, no inline HTML

`render().preview`, `renderAsync().preview`, `PreviewResult` and
`InlineModeInput` are removed, as the API removed them. Preview templates
and inline HTML in the playground, <https://play.poli.page>.
`documents().preview(id)`, the stored HTML of a rendered document, stays.

### 4. Error codes come from the API verbatim

`code()` is the API's code, never one inferred from the status: a missing
template is `TEMPLATE_NOT_FOUND`, not `NOT_FOUND`; a project missing from
the key's environment is `PROJECT_NOT_IN_ENVIRONMENT`. The version codes
(`VERSION_NOT_FOUND`, `INVALID_VERSION_FORMAT`, `VERSION_REQUIRED`,
`INVALID_VERSION_FOR_KEY_ENV`), `MISSING_PROJECT_OR_TEMPLATE`, `GONE`
(now `DOCUMENT_GONE`) and `PAYMENT_REQUIRED` are removed from
`PoliPageErrorCode`, and `PoliPagePaymentRequiredException` with them
(payment grace is `403 ORGANIZATION_PAYMENT_GRACE`, a
`PoliPageAuthException`).

### 5. `ThumbnailOptions` is all optional

Every component is optional (`width` is an `Integer`) and the builder no
longer defaults `format` to `PNG`: set it if you relied on PNG. The API,
not the SDK, now rejects out-of-range values (`400 VALIDATION_ERROR`).
`documents().thumbnails(id)` uses the API defaults.

### Surface

```java
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.input.RenderInput;

PoliPageClient client = PoliPageClient.builder()
    .apiKey(System.getenv("POLI_PAGE_API_KEY"))
    .build();

// Render facade (blocking)
//   client.render().pdf, pdfStream, document → RenderInput
//                                              (project + template + data)

// Render facade (async, returns CompletableFuture<T>)
//   client.renderAsync().pdf, pdfStream, document

// Documents facade (stored-document lifecycle)
//   client.documents().get, preview, thumbnails, delete, downloadPdf
//   client.documentsAsync().get, preview, thumbnails, delete, downloadPdf

// File helper
//   client.renderToFile(input, path), client.renderToFileAsync(input, path)
```

Behaviour matches the other Poli Page SDKs: same retry policy (5xx + 429
+ network + timeout; jitter `[0.5, 1.5)`; `Retry-After` cap 30s), same
error-code round-tripping, same predicate exceptions
(`PoliPageAuthException` covers 401 + 403), same hooks-never-break-the-request
semantics, same primitive-only `metadata`, same thumbnails wire
wrap/unwrap, same `documents().preview` `text/html` +
`X-Document-Page-Count` parsing.
