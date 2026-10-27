# Poli Page SDK for Java

[![Ci](https://img.shields.io/github/actions/workflow/status/poli-page/sdk-java/ci.yml?branch=main&style=flat&logo=githubactions&logoColor=ffffff&label=Ci)](https://github.com/poli-page/sdk-java/actions/workflows/ci.yml)
[![Codeql](https://img.shields.io/github/actions/workflow/status/poli-page/sdk-java/codeql.yml?branch=main&style=flat&logo=github&logoColor=ffffff&label=Codeql)](https://github.com/poli-page/sdk-java/actions/workflows/codeql.yml)
[![Coverage](https://img.shields.io/codecov/c/github/poli-page/sdk-java?style=flat&logo=codecov&logoColor=ffffff&label=Coverage)](https://codecov.io/gh/poli-page/sdk-java)
[![Java](https://img.shields.io/badge/Java-17%2B-blue?style=flat&logo=openjdk&logoColor=ffffff)](https://adoptium.net/)
[![Deps](https://img.shields.io/badge/Deps-up%20to%20date-brightgreen?style=flat&logo=openjdk&logoColor=ffffff)](https://github.com/poli-page/sdk-java/network/dependencies)
[![Docs](https://img.shields.io/badge/Docs-online-brightgreen?style=flat&logo=readthedocs&logoColor=ffffff)](https://poli-page.github.io/sdk-java/)
[![License](https://img.shields.io/badge/License-MIT-blue?style=flat&logo=gnu&logoColor=ffffff)](LICENSE)

Official Java SDK for [Poli Page](https://poli.page) — render polished PDFs from HTML templates via the Poli Page API.

→ **Documentation**: **<https://poli-page.github.io/sdk-java/>**
→ API reference: <https://poli-page.github.io/sdk-java/reference/client/>

## Install

`page.poli:sdk` is on Maven Central. Maven:

```xml
<dependency>
    <groupId>page.poli</groupId>
    <artifactId>sdk</artifactId>
    <version>0.10.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("page.poli:sdk:0.10.0")
```

Gradle (Groovy DSL):

```groovy
implementation 'page.poli:sdk:0.10.0'
```

Prereleases (a version with a suffix such as `-RC1`, see [CONTRIBUTING.md](CONTRIBUTING.md#stable-vs-prerelease-channels)) resolve only when you pin that exact version, e.g. `<version>X.Y.Z-RC1</version>` or `page.poli:sdk:X.Y.Z-RC1`.

Requires Java 17 or later. Runtime dependencies: `org.slf4j:slf4j-api` and `com.fasterxml.jackson.core:jackson-databind` (JSON).

## Quick start

### Render a template by slug

Set `POLI_PAGE_API_KEY` to a `sandbox` key of a fresh organization (`pp_sandbox_…`, see [Authentication & environments](#authentication--environments)), then:

```java
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.input.RenderInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class Main {
    public static void main(String[] args) throws Exception {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        byte[] pdf = client.render().pdf(RenderInput.builder()
            .project("getting-started")
            .template("welcome")
            .data(Map.of("name", "SDK Demo"))
            .build());

        Files.write(Path.of("welcome.pdf"), pdf);
    }
}
```

Without `baseUrl(...)`, the client targets `https://api.poli.page`. The SDK does not read environment variables: resolve the key (and, if you need one, the host) in your own code and pass them to the builder.

Every organization gets a `getting-started` project, published into its `sandbox` environment when the organization is created, with a `welcome` template. A sandbox key renders it with no setup, so the snippet above runs as-is on a fresh organization. It is a normal, editable project: in an organization that renamed it, deleted it or removed it from `sandbox`, the API answers `404 PROJECT_NOT_FOUND` or `404 PROJECT_NOT_IN_ENVIRONMENT`, so use a fresh organization to follow along.

There is no version to pick: a render uses the project's copy published in the API key's environment. For your own templates, publish the project to the environment of the key you render with, then swap the slugs:

```java
byte[] pdf = client.render().pdf(RenderInput.builder()
    .project("billing")
    .template("invoice")
    .data(Map.of("invoiceNumber", "INV-001", "total", 1280))
    .build());
```

To preview a template or render inline HTML while you design it, use the playground: https://play.poli.page. The API and this SDK render published templates only.

### Async

`client.renderAsync()` exposes the same methods, each returning a `CompletableFuture`:

```java
CompletableFuture<byte[]> future = client.renderAsync().pdf(RenderInput.builder()
    .project("getting-started")
    .template("welcome")
    .data(Map.of("name", "SDK Demo"))
    .build());

byte[] pdf = future.join();
```

Both surfaces share the same `PoliPageClient`: `client.render()` returns the blocking facade, `client.renderAsync()` the `CompletableFuture` facade. They sit on top of the same `java.net.http.HttpClient` connection pool.

### Try it locally — runnable demo

The repo ships an end-to-end demo that walks through the blocking workflow against the real API:

```bash
./scripts/demo
```

It reads `POLI_PAGE_API_KEY` and the optional `POLI_PAGE_BASE_URL`. Without a key, the first run prompts for a `sandbox` key (`pp_sandbox_…`) and saves it to `.env`; subsequent runs do not prompt for a key. The demo renders `getting-started`/`welcome` and walks nine steps: `render().pdf` → `render().pdfStream` → `renderToFile` → `render().document` → `documents().get` → `documents().thumbnails` → `documents().preview` → `documents().delete` → `not-found` (a render of a template that does not exist, caught as `PoliPageException` with code `TEMPLATE_NOT_FOUND`). See [`examples/demo/README.md`](examples/demo/README.md) for details.

## Working with stored documents

Every render produces a stored document, accessible via `documentId` for later download or thumbnails.

```java
// 1. Render and store
DocumentDescriptor doc = client.render().document(RenderInput.builder()
    .project("billing")
    .template("invoice")
    .data(Map.of("invoiceNumber", "INV-001"))
    .metadata(Map.of("customerId", "cust_123"))
    .build());

// 2. Persist the document ID
db.invoices().update("INV-001", doc.documentId());

// 3. Later, fetch a fresh presigned URL + download
DocumentDescriptor fresh = client.documents().get(doc.documentId());
byte[] pdf = client.documents().downloadPdf(fresh);

// 4. Generate thumbnails (Starter+ tier)
List<Thumbnail> thumbs = client.documents().thumbnails(doc.documentId(),
    ThumbnailOptions.builder()
        .width(320)
        .format(ThumbnailFormat.PNG)
        .build());

// 5. Soft-delete when done
client.documents().delete(doc.documentId());
```

The presigned URL has a ~15-minute TTL. If `downloadPdf(...)` throws `PoliPageDownloadException`, call `documents().get(id)` to refresh and retry.

## Authentication & environments

An API key belongs to exactly one environment of your organization, and its text names that environment: a key is `pp_{env}_{32 lowercase hex}`, matching

```text
^pp_(?=[a-z][a-z0-9-]{0,19}_)[a-z][a-z0-9]*(?:-[a-z0-9]+)*_[0-9a-f]{32}$
```

The API resolves the environment from the key and renders the project's copy published there; the SDK never sends an environment.

| Environment                           | Key            | Watermark | Counted |
| ------------------------------------- | -------------- | --------- | ------- |
| `sandbox` (every org)                 | `pp_sandbox_…` | yes       | no      |
| `live` (every org, deletable on Pro+) | `pp_live_…`    | no        | yes     |
| created on Pro+ (e.g. `staging`)      | `pp_staging_…` | no        | yes     |

"Counted" means the render counts toward the organization's monthly PDF quota. `sandbox` renders are watermarked, are not counted, and their documents expire 30 days after creation. There is no watermark option: a template that wants a watermark outside `sandbox` uses the `poli-watermark` class.

Standard and service keys share this one format. Pass the key with `PoliPageClient.builder().apiKey(...)`; the samples read it from the `POLI_PAGE_API_KEY` environment variable. Every key hits the same endpoint, `https://api.poli.page` by default or whatever you pass as `baseUrl` (see [Configuration](#configuration)). The SDK passes the key through as a Bearer token and only checks that it is not blank.

## Methods

The blocking surface (`client.render()`, `client.documents()`) and the async surface (`client.renderAsync()`, `client.documentsAsync()`) expose the same methods. Async variants wrap the return type in `CompletableFuture<T>`.

| Method                                                       | Returns                                | Description |
| ------------------------------------------------------------ | -------------------------------------- | ----------- |
| `client.render().pdf(input)`                                 | `byte[]`                               | Render a PDF, return bytes |
| `client.render().pdfStream(input)`                           | `InputStream`                          | Render and stream the response |
| `client.render().document(input)`                            | `DocumentDescriptor`                   | Render and return descriptor (skip auto-download) |
| `client.documents().get(id)`                                 | `DocumentDescriptor`                   | Retrieve a stored document |
| `client.documents().preview(id)`                             | `DocumentPreviewResult`                | Stored document's paginated HTML |
| `client.documents().thumbnails(id[, options])`               | `List<Thumbnail>`                      | Page thumbnails (JPEG/WebP/PNG, base64) |
| `client.documents().delete(id)`                              | `void`                                 | Soft-delete a stored document |
| `client.documents().downloadPdf(descriptor)`                 | `byte[]`                               | Fetch bytes from the descriptor's presigned URL |
| `client.renderToFile(input, path)`                           | `void`                                 | Render and stream to disk (`renderToFileAsync` returns `CompletableFuture<Void>`) |

Every render method takes a `RenderInput`: a project and one of its templates, by slug, plus the `data` that fills the template (`metadata`, `format`, `orientation` and `locale` are optional). The request body holds only those fields; there is no `version`. To pin the `Idempotency-Key` of a render, set `RenderInput.builder().idempotencyKey(...)`.

## Configuration

All options flow through `PoliPageClient.builder()`.

```java
PoliPageClient client = PoliPageClient.builder()
    .apiKey(System.getenv("POLI_PAGE_API_KEY"))
    .baseUrl(URI.create("https://api.poli.page"))
    .maxRetries(2)
    .retryDelay(Duration.ofMillis(500))
    .requestTimeout(Duration.ofSeconds(60))
    .onRetry(event -> metrics.counter("poli.retry")
        .tag("attempt", String.valueOf(event.attempt()))
        .increment())
    .onError(error -> sentry.captureException(error))
    .build();
```

| Option            | Type                       | Default                  | Description |
| ----------------- | -------------------------- | ------------------------ | ----------- |
| `apiKey`           | `String`                   | (required)               | API key of one environment, `pp_{env}_…` (e.g. `pp_sandbox_…`); see [Authentication & environments](#authentication--environments) |
| `baseUrl`          | `URI`                      | `https://api.poli.page`  | API base URL |
| `maxRetries`       | `int`                      | `2`                      | Retry budget on top of the initial attempt |
| `retryDelay`       | `Duration`                 | `500ms`                  | Base exponential-backoff delay |
| `requestTimeout`   | `Duration`                 | `60s`                    | Per-attempt deadline |
| `onRetry`          | `Consumer<RetryEvent>`     | null                     | Fires before each retry sleep |
| `onError`          | `Consumer<Throwable>`      | null                     | Fires on a terminal transport failure of an API call |
| `onRequest`        | `Consumer<RequestEvent>`   | null                     | Fires before each API attempt, retries included |
| `onResponse`       | `Consumer<ResponseEvent>`  | null                     | Fires after each API response |

The hooks cover calls to the Poli Page API only: the PDF download from the presigned URL is a single attempt outside the retry loop, and no hook fires for it.

## Error handling

The SDK throws `PoliPageException` for every API failure. The base type carries `code()`, `statusCode()`, `getMessage()`, `requestId()`, and `getCause()`. Subclasses route common cases for `catch`-based branching:

```java
try {
    byte[] pdf = client.render().pdf(input);
} catch (PoliPageAuthException e) {
    refreshCredentials();
} catch (PoliPageRateLimitException e) {
    queueForLater(e.retryAfter());
} catch (PoliPageNotFoundException e) {
    return notFound();
} catch (PoliPageValidationException e) {
    return badRequest(e.getMessage());
} catch (PoliPageException e) {
    logger.error("Poli Page error: code={} status={} requestId={}",
        e.code(), e.statusCode(), e.requestId(), e);
    throw e;
}
```

Exception hierarchy:

- `PoliPageException extends RuntimeException` — base type, thrown on any API failure.
- `PoliPageAuthException` — 401 / 403 (e.g. `INVALID_API_KEY`, `ENVIRONMENT_PROTECTED`, `ORGANIZATION_PAYMENT_GRACE`).
- `PoliPageNotFoundException` — 404 (`PROJECT_NOT_FOUND`, `PROJECT_NOT_IN_ENVIRONMENT`, `TEMPLATE_NOT_FOUND`, `DOCUMENT_NOT_FOUND`).
- `PoliPageGoneException` — 410 (`DOCUMENT_GONE`: the document was deleted).
- `PoliPageValidationException` — 400 / 422. Bad input payload.
- `PoliPageRateLimitException` — 429. Exposes `retryAfter()`.
- `PoliPageNetworkException` — DNS / TCP / TLS / timeout. Retryable.
- `PoliPageDownloadException` — presigned S3 URL fetch failed (commonly URL expiry).
- `PoliPageInvalidOptionsException` — the client builder was given invalid options (e.g. no `apiKey`).

`code()` is the API's code verbatim (compare against the constants in `PoliPageErrorCode`); a code the SDK does not know passes through unchanged.

`PoliPageException` is a `RuntimeException` so callers can choose where to handle it — Java's checked-exception model would force every call site to declare or catch otherwise, which doesn't match modern Java SDK practice (see Stripe Java, AWS SDK v2, Google Cloud client libraries).

Async callers receive the exception wrapped in `CompletionException`: unwrap with `ex.getCause()` or use `.handle((res, err) -> { … })`.

## Cancellation

### Blocking surface

Per-attempt timeout via the builder's `requestTimeout` (default 60s). When the timeout expires and the retry budget is spent, the SDK throws `PoliPageException` with `code() == PoliPageErrorCode.TIMEOUT`.

```java
PoliPageClient client = PoliPageClient.builder()
    .apiKey(System.getenv("POLI_PAGE_API_KEY"))
    .requestTimeout(Duration.ofSeconds(5))
    .build();
```

For blocking thread cancellation, interrupt the worker thread — the SDK propagates `InterruptedException` (wrapped in `PoliPageException` with `code() == PoliPageErrorCode.ABORTED`). Caller-aborted operations are never retried.

### Async surface

Cancel the returned `CompletableFuture`:

```java
CompletableFuture<byte[]> future = client.renderAsync().pdf(input);
ScheduledFuture<?> deadline = scheduler.schedule(() -> future.cancel(true),
    5, TimeUnit.SECONDS);

future
    .thenAccept(pdf -> { … })
    .whenComplete((res, err) -> deadline.cancel(false));
```

`future.cancel(true)` interrupts the underlying HTTP attempt and triggers the SDK's `CancellationException`-path. Cancelled futures are never retried.

## Observability

Register hooks on the builder to feed your own logger, metrics or tracing:

```java
PoliPageClient.builder()
    .apiKey(System.getenv("POLI_PAGE_API_KEY"))
    .onRequest(event -> log.debug("→ {} {} (attempt {})",
        event.method(), event.url(), event.attempt()))
    .onResponse(event -> log.debug("← {} in {}ms requestId={}",
        event.status(), event.durationMs(), event.requestId()))
    .onRetry(event -> metrics.counter("poli.retry")
        .tag("attempt", String.valueOf(event.attempt()))
        .increment())
    .onError(error -> sentry.captureException(error))
    .build();
```

Hooks are synchronous, optional, and exception-safe — a hook that throws never breaks the request. The events never carry the `Authorization` header. They fire for API calls only, not for the presigned-URL PDF download.

## Retries & idempotency

The SDK retries on **5xx**, **429**, **network errors**, and **timeouts**. Backoff is exponential (`retryDelay × 2^N`) with jitter in `[0.5, 1.5)`, capped at the server's `Retry-After` header when provided (max 30s). Every POST sends an auto-generated `Idempotency-Key` (UUID v4); set `idempotencyKey(...)` on the `RenderInput` builder to choose it yourself:

```java
client.render().pdf(RenderInput.builder()
    .project("billing")
    .template("invoice")
    .data(Map.of("invoiceNumber", "INV-001"))
    .idempotencyKey("inv-INV-001")
    .build());
```

Disable retries by setting `.maxRetries(0)` on the client builder.

## Type system

The SDK ships full Javadoc for every public symbol — they surface in IDE hover (`./mvnw javadoc:javadoc` builds it locally).

- `RenderInput` is a record built with `RenderInput.builder()`; it is the only render input. `project`, `template` and `data` are required, and `build()` throws `NullPointerException` when one is missing.
- `PoliPageException` is a sealed class: pattern-match against its eight final subclasses plus the base.
- Nullable wire fields are `@Nullable` record components; primitives use boxed types (`Integer`) when nullable.
- Public types are annotated with JSpecify `@NullMarked` / `@Nullable` so IDEs and Kotlin consumers get accurate nullability information.

Consumers should enable `errorprone` or `-Xlint:all -Werror` in their build to surface the SDK's nullability contract.

## Concurrency & thread-safety

`PoliPageClient` is immutable after `build()` and thread-safe. Share **one instance per process** across all threads — the underlying `java.net.http.HttpClient` pools connections automatically.

Parallel requests fan out cleanly via `CompletableFuture.allOf`:

```java
List<CompletableFuture<DocumentDescriptor>> tasks = invoices.stream()
    .map(invoice -> client.renderAsync().document(RenderInput.builder()
        .project("billing")
        .template("invoice")
        .data(invoice.toData())
        .build()))
    .toList();

CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
    .thenApply(v -> tasks.stream().map(CompletableFuture::join).toList())
    .thenAccept(results -> { /* … */ });
```

## Runtime support

Server-side only. The SDK runs on:

- **Java 17 LTS** — minimum supported version.
- **Java 21 LTS** — primary recommended runtime.
- **Java 25 LTS** — tested in CI.

Compatible JVMs: OpenJDK, Temurin, Amazon Corretto, Azul Zulu, GraalVM (including Native Image).

Kotlin consumers (Kotlin 1.9+) and Scala consumers (3.x) use the same JAR through normal JVM interop — no separate publishing targets.

**Browsers / GWT are not supported.** API keys (`pp_{env}_…`, whatever the environment) are secrets and must never be shipped to a browser. Call the SDK from your backend (Spring Boot, Quarkus, Micronaut, Helidon, plain `main`, AWS Lambda) and proxy the result to the client.

CI exercises Java 17, 21, and 25 on Linux, plus Java 21 on Windows and macOS.

## Requirements

- Java 17 LTS or later.
- Outbound HTTPS to `api.poli.page` and the presigned S3 hosts the API returns for downloads.
- Runtime dependencies: `org.slf4j:slf4j-api` ≥ 2.0 and `com.fasterxml.jackson.core:jackson-databind` ≥ 2.17. Both are widely present in modern Java apps.
- No native dependencies — pure Java.

## Documentation & support

- Platform docs: [docs.poli.page](https://docs.poli.page)
- SDK reference: [poli-page.github.io/sdk-java](https://poli-page.github.io/sdk-java/reference/client/)
- Sign up & generate API keys: [app.poli.page](https://app.poli.page)
- Issues: [github.com/poli-page/sdk-java/issues](https://github.com/poli-page/sdk-java/issues)
- Support: contact@poli.page

## License

[MIT](LICENSE) © Poli Page
