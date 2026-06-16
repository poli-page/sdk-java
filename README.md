# Poli Page SDK for Java

[![Maven Central](https://img.shields.io/maven-central/v/page.poli/sdk.svg)](https://central.sonatype.com/artifact/page.poli/sdk)
[![Javadoc](https://javadoc.io/badge2/page.poli/sdk/javadoc.svg)](https://javadoc.io/doc/page.poli/sdk)
[![CI](https://github.com/poli-page/sdk-java/actions/workflows/ci.yml/badge.svg)](https://github.com/poli-page/sdk-java/actions/workflows/ci.yml)
[![CodeQL](https://github.com/poli-page/sdk-java/actions/workflows/codeql.yml/badge.svg)](https://github.com/poli-page/sdk-java/actions/workflows/codeql.yml)
[![codecov](https://codecov.io/gh/poli-page/sdk-java/branch/main/graph/badge.svg)](https://codecov.io/gh/poli-page/sdk-java)
[![Java 17+](https://img.shields.io/badge/Java-17%2B-blue.svg)](https://adoptium.net/)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

Official Java SDK for [Poli Page](https://poli.page) — render polished PDFs from HTML templates via the Poli Page API.

→ **Documentation**: **<https://poli-page.github.io/sdk-java/>**
→ API reference on javadoc.io: <https://javadoc.io/doc/page.poli/sdk>

## Install

Maven:

```xml
<dependency>
    <groupId>page.poli</groupId>
    <artifactId>sdk</artifactId>
    <version>1.0.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("page.poli:sdk:1.0.0")
```

Gradle (Groovy):

```groovy
implementation 'page.poli:sdk:1.0.0'
```

Requires Java 17 or later. Runtime dependencies: `org.slf4j:slf4j-api` (logging facade) and `com.fasterxml.jackson.core:jackson-databind` (JSON).

## Quick start

### Async

```java
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.input.ProjectModeInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class Main {
    public static void main(String[] args) throws Exception {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        CompletableFuture<byte[]> future = client.renderAsync().pdf(ProjectModeInput.builder()
            .project("getting-started")
            .template("welcome")
            .version("1.0.0")
            .data(Map.of("name", "World"))
            .build());

        byte[] pdf = future.join();
        Files.write(Path.of("welcome.pdf"), pdf);
        // pdf is a byte[] of the rendered PDF document
    }
}
```

Every Poli Page org comes pre-provisioned with a `getting-started/welcome` template, so the snippet above runs as-is the moment you have an API key. Swap the slugs once you've pushed your own templates with the `poli` CLI.

### Blocking

```java
byte[] pdf = client.render().pdf(ProjectModeInput.builder()
    .project("billing")
    .template("invoice")
    .version("1.0.0")
    .data(Map.of("invoiceNumber", "INV-001"))
    .build());

Files.write(Path.of("invoice.pdf"), pdf);
```

Both surfaces share the same `PoliPageClient` — `client.render()` returns the blocking facade, `client.renderAsync()` returns the `CompletableFuture` facade. They sit on top of the same `java.net.http.HttpClient` connection pool.

## Working with stored documents

Every render produces a stored document, accessible via `documentId` for later download or thumbnails.

```java
// 1. Render and store
DocumentDescriptor doc = client.render().document(ProjectModeInput.builder()
    .project("billing")
    .template("invoice")
    .version("1.0.0")
    .data(Map.of("invoiceNumber", "INV-001"))
    .metadata(Map.of("customerId", "cust_123"))
    .build());

// 2. Persist the document ID
db.invoices().update("INV-001", doc.documentId());

// 3. Later, fetch a fresh presigned URL + download
DocumentDescriptor fresh = client.documents().get(doc.documentId());
byte[] pdf = fresh.downloadPdf();

// 4. Generate thumbnails (Starter+ tier)
List<Thumbnail> thumbs = client.documents().thumbnails(doc.documentId(),
    ThumbnailOptions.builder()
        .width(320)
        .format(ThumbnailFormat.PNG)
        .build());

// 5. Soft-delete when done
client.documents().delete(doc.documentId());
```

The presigned URL has a ~15-minute TTL. If `downloadPdf()` throws `PoliPageDownloadException`, call `documents().get(id)` to refresh and retry.

## Authentication & environments

The API key is read from the `POLI_PAGE_API_KEY` environment variable by default, or passed via `PoliPageClient.builder().apiKey(...)`. The mode is determined by the key prefix:

| Prefix       | Mode                                                               |
| ------------ | ------------------------------------------------------------------ |
| `pp_test_…`  | Sandbox — not billed, generous rate limits                          |
| `pp_live_…`  | Live — billed, production rate limits                              |
| `pp_sa_…`    | Service-account key; environment matches the SA's configuration    |

All prefixes hit the same endpoint (`https://api.poli.page`). The SDK passes the key as a Bearer token and never inspects the prefix.

## Methods

The blocking surface (`client.render()`, `client.documents()`) and the async surface (`client.renderAsync()`, `client.documentsAsync()`) expose the same methods. Async variants wrap the return type in `CompletableFuture<T>`.

| Method                                                       | Returns                                | Description |
| ------------------------------------------------------------ | -------------------------------------- | ----------- |
| `client.render().pdf(input)`                                 | `byte[]`                               | Render a PDF, return bytes |
| `client.render().pdfStream(input)`                           | `InputStream`                          | Render and stream the response |
| `client.render().preview(input)`                             | `PreviewResult`                        | Paginated HTML preview |
| `client.render().document(input)`                            | `DocumentDescriptor`                   | Render and return descriptor (skip auto-download) |
| `client.documents().get(id)`                                 | `DocumentDescriptor`                   | Retrieve a stored document |
| `client.documents().preview(id)`                             | `DocumentPreviewResult`                | Stored document's paginated HTML |
| `client.documents().thumbnails(id, options)`                 | `List<Thumbnail>`                      | Page thumbnails (PNG/JPEG, base64) |
| `client.documents().delete(id)`                              | `void`                                 | Soft-delete a stored document |
| `descriptor.downloadPdf()`                                   | `byte[]`                               | Fetch bytes from the descriptor's presigned URL |
| `PoliPageClient.renderToFile(input, path)`                   | `void`                                 | Render and stream to disk |

`render().pdf(...)`, `pdfStream(...)`, and `document(...)` accept `ProjectModeInput` only — passing `InlineModeInput` is a compile-time error (the parameter type is `ProjectModeInput`, not the sealed `RenderInput`). `render().preview(...)` accepts the sealed `RenderInput`, satisfied by both modes.

Every method accepts an optional final `RequestOptions` argument for per-call overrides (idempotency key, request timeout, additional headers).

## Configuration

All options flow through `PoliPageClient.builder()`. Per-call options (`idempotencyKey`, `requestTimeout`, `headers`) are passed via the optional `RequestOptions` argument to individual methods.

```java
PoliPageClient client = PoliPageClient.builder()
    .apiKey(System.getenv("POLI_PAGE_API_KEY"))
    .baseUrl(URI.create("https://api.poli.page"))
    .maxRetries(2)
    .retryDelay(Duration.ofMillis(500))
    .requestTimeout(Duration.ofSeconds(60))
    .httpClient(HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build())
    .logger(LoggerFactory.getLogger(PoliPageClient.class))
    .onRetry(event -> metrics.counter("poli.retry")
        .tag("attempt", String.valueOf(event.attempt()))
        .increment())
    .onError(error -> sentry.captureException(error))
    .build();

// Per-call overrides
byte[] pdf = client.render().pdf(input, RequestOptions.builder()
    .idempotencyKey("inv-INV-001")
    .requestTimeout(Duration.ofSeconds(5))
    .header("X-Trace-Id", traceId)
    .build());
```

| Option            | Type                       | Default                  | Description |
| ----------------- | -------------------------- | ------------------------ | ----------- |
| `apiKey`           | `String`                   | (required)               | `pp_test_*`, `pp_live_*`, or `pp_sa_*` API key |
| `baseUrl`          | `URI`                      | `https://api.poli.page`  | API base URL |
| `maxRetries`       | `int`                      | `2`                      | Retry budget on top of the initial attempt |
| `retryDelay`       | `Duration`                 | `500ms`                  | Base exponential-backoff delay |
| `requestTimeout`   | `Duration`                 | `60s`                    | Per-request deadline (overridable per call) |
| `httpClient`       | `java.net.http.HttpClient` | new instance             | Inject custom transport / executor |
| `logger`           | `org.slf4j.Logger`         | `NOPLogger`              | One DEBUG/attempt, WARN/retry, ERROR/terminal |
| `onRetry`          | `Consumer<RetryEvent>`     | null                     | Fires before each retry sleep |
| `onError`          | `Consumer<Throwable>`      | null                     | Fires on terminal failure |

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
- `PoliPageAuthException` — 401 / 403. Covers `Unauthorized`, `Forbidden`.
- `PoliPageNotFoundException` — 404. Covers `NotFound`, `VersionNotFound`, `DocumentNotFound`.
- `PoliPageGoneException` — 410. Document was soft-deleted.
- `PoliPageValidationException` — 400 / 422. Bad input payload.
- `PoliPageRateLimitException` — 429. Exposes `retryAfter()`.
- `PoliPagePaymentRequiredException` — 402. Subscription has unpaid invoices.
- `PoliPageNetworkException` — DNS / TCP / TLS / timeout. Retryable.
- `PoliPageDownloadException` — presigned S3 URL fetch failed (commonly URL expiry).

`PoliPageException` is a `RuntimeException` so callers can choose where to handle it — Java's checked-exception model would force every call site to declare or catch otherwise, which doesn't match modern Java SDK practice (see Stripe Java, AWS SDK v2, Google Cloud client libraries).

Async callers receive the exception wrapped in `CompletionException`: unwrap with `ex.getCause()` or use `.handle((res, err) -> { … })`.

## Cancellation

### Blocking surface

Per-request timeout via `PoliPageClientOptions.requestTimeout` (default 60s) or per-call `RequestOptions.requestTimeout`. When the timeout expires, the SDK throws `PoliPageException` with `code() == PoliPageErrorCode.TIMEOUT`.

```java
byte[] pdf = client.render().pdf(input, RequestOptions.builder()
    .requestTimeout(Duration.ofSeconds(5))
    .build());
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

The SDK integrates with the SLF4J facade:

```java
PoliPageClient client = PoliPageClient.builder()
    .apiKey(System.getenv("POLI_PAGE_API_KEY"))
    .logger(LoggerFactory.getLogger(PoliPageClient.class))
    .build();
```

One DEBUG line per HTTP attempt, one WARN per retry, one ERROR per terminal failure. The `Authorization` header is never logged.

For SDK-level events, register hooks:

```java
PoliPageClient.builder()
    .onRetry(event -> metrics.counter("poli.retry")
        .tag("attempt", String.valueOf(event.attempt()))
        .increment())
    .onError(error -> sentry.captureException(error))
    .build();
```

Hooks are synchronous, optional, and exception-safe — a hook that throws never breaks the request. For full HTTP-level inspection (headers + body), inject a custom `java.net.http.HttpClient` and add a `java.net.http.HttpClient.Builder.proxy(...)` or a logging filter.

The SDK emits OpenTelemetry spans via the instrumentation API under the source name `page.poli.sdk` — register it on your `SdkTracerProvider` to capture spans automatically.

## Retries & idempotency

The SDK retries on **5xx**, **429**, **network errors**, and **timeouts**. Backoff is exponential (`retryDelay × 2^N`) with jitter in `[0.5, 1.5)`, capped at the server's `Retry-After` header when provided (max 30s). Every POST sends an auto-generated `Idempotency-Key` (UUID v4); pass `RequestOptions.idempotencyKey(...)` to override:

```java
client.render().pdf(input, RequestOptions.builder()
    .idempotencyKey("inv-INV-001")
    .build());
```

Disable retries by setting `.maxRetries(0)` on the client builder.

## Type system

The SDK ships full Javadoc for every public symbol — they surface in IDE hover and feed the published API reference on javadoc.io.

- Input types (`ProjectModeInput`, `InlineModeInput`) are records sealed by the abstract `RenderInput` permitted-subtypes list. External types cannot extend it.
- Methods that require project mode (`pdf`, `pdfStream`, `document`) accept `ProjectModeInput` directly — passing `InlineModeInput` is a compile-time error.
- Nullable wire fields use `Optional<T>` on accessors for the boundary cases where present-vs-null matters; primitives use boxed types (`Long`, `Integer`) when nullable.
- Public types are annotated with JSpecify `@NullMarked` / `@Nullable` so IDEs and Kotlin consumers get accurate nullability information.

Consumers should enable `errorprone` or `-Xlint:all -Werror` in their build to surface the SDK's nullability contract.

## Concurrency & thread-safety

`PoliPageClient` is immutable after `build()` and thread-safe. Share **one instance per process** across all threads — the underlying `java.net.http.HttpClient` pools connections automatically and is the BCL's recommended sharing model.

Parallel requests fan out cleanly via `CompletableFuture.allOf`:

```java
List<CompletableFuture<DocumentDescriptor>> tasks = invoices.stream()
    .map(invoice -> client.renderAsync().document(ProjectModeInput.builder()
        .project("billing")
        .template("invoice")
        .version("1.0.0")
        .data(invoice.toData())
        .build()))
    .toList();

CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
    .thenApply(v -> tasks.stream().map(CompletableFuture::join).toList())
    .thenAccept(results -> { /* … */ });
```

Tune the executor with `.httpClient(HttpClient.newBuilder().executor(myExecutor).build())` if the default `ForkJoinPool.commonPool()` doesn't fit your workload.

## Runtime support

Server-side only. The SDK runs on:

- **Java 17 LTS** — minimum supported version.
- **Java 21 LTS** — primary recommended runtime.
- **Java 24** — current non-LTS, tested in CI.

Compatible JVMs: OpenJDK, Temurin, Amazon Corretto, Azul Zulu, GraalVM (including Native Image).

Kotlin consumers (Kotlin 1.9+) and Scala consumers (3.x) use the same JAR through normal JVM interop — no separate publishing targets.

**Browsers / GWT are not supported.** API keys (`pp_test_*`, `pp_live_*`) are secrets and must never be shipped to a browser. Call the SDK from your backend (Spring Boot, Quarkus, Micronaut, Helidon, plain `main`, AWS Lambda) and proxy the result to the client.

CI exercises Java 17, 21, and 24 on Linux, plus Java 21 on Windows and macOS.

## Requirements

- Java 17 LTS or later.
- Outbound HTTPS to `api.poli.page` and the presigned S3 hosts the API returns for downloads.
- Runtime dependencies: `org.slf4j:slf4j-api` ≥ 2.0 and `com.fasterxml.jackson.core:jackson-databind` ≥ 2.17. Both are widely present in modern Java apps.
- No native dependencies — pure Java.

## Documentation & support

- Platform docs: [docs.poli.page](https://docs.poli.page)
- SDK API reference: [javadoc.io/doc/page.poli/sdk](https://javadoc.io/doc/page.poli/sdk)
- Sign up & generate API keys: [app.poli.page](https://app.poli.page)
- Issues: [github.com/poli-page/sdk-java/issues](https://github.com/poli-page/sdk-java/issues)
- Support: support@poli.page

## License

[MIT](LICENSE) © Poli Page
