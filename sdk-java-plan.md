# Poli Page SDK for Java — Implementation Plan

**Created**: 2026-05-29
**Author**: handoff doc, not a spec — the spec lives at `/Users/mickael/Projects/sdk-node/sdk-specification.md` v1.3
**Reference impl**: `/Users/mickael/Projects/sdk-node` (`@poli-page/sdk` v1.0, shipped). See also `/Users/mickael/Projects/sdk-go/sdk-go-plan.md` and `/Users/mickael/Projects/sdk-csharp/sdk-csharp-plan.md` for the statically-typed siblings.
**Empirical API source of truth**: the CLI's api-client at `/Users/mickael/n/lib/node_modules/@poli-page/cli/dist/api-client.{js,d.ts}` — works end-to-end against `api-develop.poli.page`. When spec and deployed API disagree, the CLI's behavior wins.

This document is the briefing for a fresh Claude session in the new `sdk-java` repo. Everything a new conversation needs to start work — links to source-of-truth files, design decisions already made, the build order, and the open questions — is captured here.

---

## 0. TL;DR

We are shipping the Java sibling of `@poli-page/sdk`. The contract is fixed (`sdk-specification.md` v1.3); we are translating an already-shipped TypeScript reference implementation into idiomatic Java. The published artifact is `page.poli:sdk` on Maven Central; the root package is `page.poli.sdk`.

The plan: single Maven artifact targeting Java 17 LTS as the floor (CI matrix Java 17, 21, 25). One immutable `PoliPageClient` built via `PoliPageClient.builder()` (the JVM idiom — telescoping constructors don't scale past four args). Two facades on the same client: a **blocking** facade (`client.render()`, `client.documents()`) that returns plain values and a `CompletableFuture`-based **async** facade (`client.renderAsync()`, `client.documentsAsync()`) returning the same values wrapped in futures. Both sit on top of one shared `java.net.http.HttpClient` connection pool — the BCL HTTP client is non-blocking under the hood and works for both modes equally well. `Jackson` for wire serialization (the only zero-friction choice for the modern JVM ecosystem); `SLF4J` for logging facade; `java.util.UUID` for idempotency keys. JUnit 5 + AssertJ + WireMock for tests. Examples shipped under `examples/` (Spring Boot, Quarkus, Micronaut, AWS Lambda, plain main) plus a `demo` runnable. CI on GitHub Actions across **Java 17, 21, 25** on Ubuntu + **Java 21** on Windows and macOS — matches the engineering guide §4.1 ("LTS (17, 21) + current"; Java 25 was the LTS GA in September 2025). Manual local pre-flight via `./mvnw verify -P release`; manual `./mvnw deploy -P release` to the Sonatype Central staging repo via `central-publishing-maven-plugin`; closure + release happens through the Sonatype Central Portal UI.

**No Maven Central auto-publish.** Per the engineering guide §6.4 there MUST NOT be a CI workflow that publishes on tag push without manual intervention. Maven Central artifacts are immutable and the GPG-signed staging step is the natural human checkpoint; we keep it.

Ship in 8 phases (see §13) — same shape as the C# sibling. Target: feature-parity 1.0.0 release, behavior-identical to `@poli-page/sdk@1.0.0`. **"Behavior parity" specifically means: same retry policy (5xx+429+network+timeout, jitter `[0.5,1.5)`, Retry-After cap 30s), same error codes round-tripped verbatim, same `PoliPageAuthException` covering 401+403, same constructor validation, same hooks-never-break-the-request, same project-mode-only constraint on `render().pdf` / `render().pdfStream` / `render().document`, same primitive-only `metadata`, same thumbnails wire wrap/unwrap, same `documents().preview` text/html + `X-Document-Page-Count` parsing.**

---

## 1. Source-of-truth references

Read these in order before writing a single line of code:

1. **Multi-language spec** — `/Users/mickael/Projects/sdk-node/sdk-specification.md` (v1.3). Defines the contract every SDK must meet: methods, options, errors, retry policy, HTTP rules, tier requirements.
2. **SDK engineering guide** — `/Users/mickael/Projects/sdk-node/sdk-engineering-guide.md`. Cross-SDK policy: versioning, CHANGELOG/MIGRATION discipline, CI gates, language-version matrices, release flow, pre-push hooks. **Authoritative** — when this plan and the engineering guide disagree on conventions, the engineering guide wins.
3. **SDK README convention** — `/Users/mickael/Projects/SDK_README_CONVENTION.md`. The 16-H2 structure every SDK README MUST follow, including Java's two-H3 Quick start (`Blocking` then `Async`).
4. **SDK roadmap** — `/Users/mickael/Projects/sdk-node/sdk-roadmap.md` v1.3. Multi-repo strategy across all SDKs and the 12-repo target.
5. **Node SDK source** — `/Users/mickael/Projects/sdk-node/src/`. Reference implementation. When the spec is silent, the Node SDK's behavior is canonical (spec §11).
6. **Node SDK tests** — `/Users/mickael/Projects/sdk-node/tests/`. Especially `tests/integration/` for what the deployed API actually returns, and `tests/error-codes.test.ts` for the full error matrix.
7. **C# sibling plan** — `/Users/mickael/Projects/sdk-csharp/sdk-csharp-plan.md`. Shares every architectural decision for a statically-typed enterprise SDK with both async and DI integration; when in doubt, mirror it with Java substitutions. The Java-specific divergences are catalogued in §18 of this doc.
8. **CLI api-client** — `/Users/mickael/n/lib/node_modules/@poli-page/cli/dist/api-client.{js,d.ts}`. Empirical source of truth for the deployed API. If the spec and the CLI disagree, the CLI is right.

Anytime you're unsure about a behavior, check the Node SDK source. If the Node SDK behavior is wrong, that's a separate bug — fix it in `sdk-node` first, then port; don't diverge silently.

---

## 2. Naming and identity

Per spec §2:

| Field | Value |
|---|---|
| **Maven coordinates** | `page.poli:sdk` |
| **Root package** | `page.poli.sdk` |
| **Client type** | `PoliPageClient` (constructed via `PoliPageClient.builder()…build()`) |
| **Method casing** | camelCase (Java idiom) — `render().pdf`, `render().pdfStream`, `render().preview`, `render().document`, `documents().get`, `documents().preview`, `documents().thumbnails`, `documents().delete` |
| **Async suffix** | Facade method (`client.renderAsync()`) not method-level (`pdfAsync`). The facade is the seam — methods inside it share the convention. |
| **Exception base** | `PoliPageException extends RuntimeException` (mirrors Node's `PoliPageError`) |
| **File helper** | `PoliPageClient.renderToFile(input, path)` — static method on the client class |
| **Artifact version** | Maven semver; start at `1.0.0`. Prerelease pattern `1.0.0-RC1` (uppercase, no dot — Maven idiom). |
| **Version string** | `Version.VALUE` constant in `page.poli.sdk.internal.Version`, bumped manually on each release. Embedded into the `User-Agent` header. |
| **User-Agent** | `poli-page-sdk-java/<version>` |

Field names on records / option types follow Java camelCase. The wire JSON uses camelCase too, so most fields need no mapping. Where Jackson's default differs (record component name vs JSON field name), use `@JsonProperty("…")`.

The wire serializer is Jackson's `ObjectMapper` configured with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false` — so adding fields to the wire payload is backwards-compatible for older client versions.

---

## 3. Architecture

### 3.1 Project layout

```
sdk-java/
├── pom.xml                                  # parent POM, dependency versions, plugin config
├── mvnw, mvnw.cmd                           # Maven Wrapper
├── .mvn/wrapper/                            # Maven Wrapper jar + properties
├── .editorconfig
├── src/
│   ├── main/java/page/poli/sdk/
│   │   ├── PoliPageClient.java              # client + Builder + namespace accessors + renderToFile helper
│   │   ├── PoliPageClientOptions.java       # internal record holding final option values (Builder produces this)
│   │   ├── RequestOptions.java              # per-call options + Builder
│   │   ├── Render.java                      # blocking render facade
│   │   ├── RenderAsync.java                 # CompletableFuture-based render facade
│   │   ├── Documents.java                   # blocking documents facade
│   │   ├── DocumentsAsync.java              # CompletableFuture-based documents facade
│   │   ├── input/
│   │   │   ├── RenderInput.java             # sealed abstract base
│   │   │   ├── ProjectModeInput.java        # record implements RenderInput (project + template + version)
│   │   │   └── InlineModeInput.java         # record implements RenderInput (template HTML)
│   │   ├── model/
│   │   │   ├── DocumentDescriptor.java      # record with downloadPdf() instance method
│   │   │   ├── DocumentPreviewResult.java
│   │   │   ├── PreviewResult.java
│   │   │   ├── Thumbnail.java
│   │   │   ├── ThumbnailOptions.java
│   │   │   ├── ThumbnailFormat.java         # enum PNG, JPEG
│   │   │   ├── RenderMetadata.java          # typed alias over Map<String, Object>
│   │   │   └── RetryEvent.java              # record(attempt, delay, statusCode, reason)
│   │   ├── exception/
│   │   │   ├── PoliPageException.java       # base RuntimeException — code, statusCode, requestId
│   │   │   ├── PoliPageAuthException.java
│   │   │   ├── PoliPageNotFoundException.java
│   │   │   ├── PoliPageGoneException.java
│   │   │   ├── PoliPageValidationException.java
│   │   │   ├── PoliPageRateLimitException.java    # retryAfter
│   │   │   ├── PoliPagePaymentRequiredException.java
│   │   │   ├── PoliPageNetworkException.java
│   │   │   └── PoliPageDownloadException.java
│   │   ├── PoliPageErrorCode.java           # static constants
│   │   ├── internal/
│   │   │   ├── Transport.java               # internal seam interface
│   │   │   ├── HttpTransport.java           # default impl wrapping java.net.http.HttpClient
│   │   │   ├── RetryLoop.java               # synchronous + async retry orchestration (shared math)
│   │   │   ├── Backoff.java                 # PURE — computeBackoff(attempt, baseDelay), jitter, cap
│   │   │   ├── Headers.java                 # PURE — buildHeaders, parseRetryAfter
│   │   │   ├── Urls.java                    # PURE — buildUrl, path templates
│   │   │   ├── ErrorParsing.java            # PURE — wire → PoliPage*Exception mapping
│   │   │   ├── Uuid.java                    # UUID.randomUUID() wrapper for testability
│   │   │   └── Version.java                 # const VALUE for User-Agent
│   │   └── package-info.java                # @NullMarked + Javadoc package summary
│   └── test/java/page/poli/sdk/
│       ├── PoliPageClientTest.java
│       ├── RenderTest.java
│       ├── RenderAsyncTest.java
│       ├── DocumentsTest.java
│       ├── DocumentsAsyncTest.java
│       ├── ErrorMappingTest.java
│       ├── RetryLoopTest.java
│       └── internal/
│           ├── BackoffTest.java
│           └── HeadersTest.java
├── src/integrationTest/java/page/poli/sdk/  # gated by Maven `integration-tests` profile
│   └── EndToEndTest.java
├── examples/
│   ├── demo/                                # Runnable demo — NOT a release artifact
│   │   ├── pom.xml
│   │   └── src/main/java/page/poli/sdk/examples/demo/Main.java
│   ├── spring-boot/
│   ├── quarkus/
│   ├── micronaut/
│   └── aws-lambda/
├── testdata/
│   └── templates/invoice/                   # Copied from sdk-node/demo/templates/ for cross-lang byte-diffability
├── .github/
│   ├── dependabot.yml                       # maven + github-actions, weekly schedule (engineering guide §4.12)
│   └── workflows/
│       ├── ci.yml                           # restore + spotless + compile + test + package on Java 17/21/25
│       ├── integration.yml                  # nightly + push-to-main; hits develop API
│       ├── codeql.yml                       # CodeQL language: java (engineering guide §4.11)
│       └── release.yml                      # workflow_dispatch — gated by environment with required reviewers
├── scripts/
│   ├── publish.sh                           # primary local publishing path: verify + sign + stage + deploy + tag
│   └── install-hooks.sh                     # writes .git/hooks/pre-push
├── README.md
├── CHANGELOG.md                             # keepachangelog format, mirror sdk-node
├── MIGRATION.md
├── SECURITY.md
├── CONTRIBUTING.md
├── LICENSE                                  # MIT
├── CLAUDE.md                                # this repo's CLAUDE.md
├── sdk-engineering-guide.md                 # copy of the shared cross-SDK engineering guide
├── sdk-roadmap.md                           # copy of the shared roadmap (v1.3 — includes Java as P6)
└── sdk-java-plan.md                         # this document
```

Reasoning: the **transport core** (URL/header building, retry math, error parsing) is pure functions in `internal/`. Java's package-private + `module-info.java` (if we add a JPMS module — open question) is the visibility boundary. The public `PoliPageClient` orchestrates retries by composing two seams: a synchronous retry loop (`RetryLoop.executeSync`) for the blocking facade and an async retry loop (`RetryLoop.executeAsync`) for the `CompletableFuture` facade. Both wrap the same `HttpTransport` and share the same `Backoff` math.

This mirrors Node's `internal/http.ts` (pure) + `index.ts` (orchestration) + `render.ts`/`documents.ts` (namespace impls) split. Same architecture, JVM idioms.

### 3.2 The transport seam

```java
// package-private — exposed via internal/ for tests via reflection or a test-source-set fixture.
interface Transport {
    HttpResponse<byte[]>      post(String path, Object body, String idempotencyKey, RequestOptions opts) throws IOException, InterruptedException;
    CompletableFuture<HttpResponse<byte[]>> postAsync(String path, Object body, String idempotencyKey, RequestOptions opts);
    HttpResponse<byte[]>      get(String path, RequestOptions opts) throws IOException, InterruptedException;
    CompletableFuture<HttpResponse<byte[]>> getAsync(String path, RequestOptions opts);
    void                      delete(String path, RequestOptions opts) throws IOException, InterruptedException;
    CompletableFuture<Void>   deleteAsync(String path, RequestOptions opts);
}
```

`HttpTransport` wraps `java.net.http.HttpClient`. The blocking variants call `HttpClient.send(...)`; the async variants call `HttpClient.sendAsync(...)`. Both flow through the same `RetryLoop` for backoff and error mapping.

**Per the Node SDK's n2 design memory**: design all four verbs from day one. Don't repeat the Node mistake of building a POST-only request method and needing to widen it for GET/DELETE later.

### 3.3 Public surface

```java
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.RequestOptions;
import page.poli.sdk.input.ProjectModeInput;
import page.poli.sdk.exception.PoliPageAuthException;
import page.poli.sdk.exception.PoliPageException;

PoliPageClient client = PoliPageClient.builder()
    .apiKey(System.getenv("POLI_PAGE_API_KEY"))
    .maxRetries(3)
    .build();

try {
    byte[] pdf = client.render().pdf(ProjectModeInput.builder()
            .project("billing")
            .template("invoice")
            .version("1.0.0")
            .data(Map.of("invoiceNumber", "INV-001"))
            .build(),
        RequestOptions.builder()
            .idempotencyKey("inv-INV-001")
            .build());

    Files.write(Path.of("invoice.pdf"), pdf);
} catch (PoliPageAuthException e) {
    // refresh credentials
} catch (PoliPageException e) {
    logger.error("Poli Page error: code={} status={} requestId={}",
        e.code(), e.statusCode(), e.requestId(), e);
    throw e;
}
```

The package exports:
- `PoliPageClient`, `PoliPageClient.Builder`, `RequestOptions`, `RequestOptions.Builder`
- `Render`, `RenderAsync`, `Documents`, `DocumentsAsync` — facade types
- `RenderInput` (sealed abstract), `ProjectModeInput`, `InlineModeInput`, `RenderMetadata`
- `DocumentDescriptor`, `DocumentPreviewResult`, `PreviewResult`, `Thumbnail`, `ThumbnailOptions`, `ThumbnailFormat`
- `PoliPageException` + 8 subclasses + `PoliPageErrorCode` constants
- `RetryEvent` record for the `onRetry` hook

`client.render()`, `client.renderAsync()`, `client.documents()`, `client.documentsAsync()` each return the **same cached facade instance** for the lifetime of the `PoliPageClient` — they are constructed once in `PoliPageClient`'s constructor and the accessor methods are pure getters. This matches Azure SDK for Java and AWS SDK v2: no allocation per call, no surprising identity behaviour for users who hold references.

### 3.4 DocumentDescriptor and downloadPdf

`DocumentDescriptor` is a **pure value record** — fully serializable, free of any hidden owner reference. It mirrors the wire shape verbatim:

```java
public record DocumentDescriptor(
        @JsonProperty("documentId")     String documentId,
        @JsonProperty("organizationId") String organizationId,
        @JsonProperty("projectId")      @Nullable String projectId,
        @JsonProperty("projectSlug")    @Nullable String projectSlug,
        @JsonProperty("templateId")     @Nullable String templateId,
        @JsonProperty("templateSlug")   @Nullable String templateSlug,
        @JsonProperty("version")        @Nullable String version,
        @JsonProperty("environment")    String environment,
        @JsonProperty("format")         String format,
        @JsonProperty("pageCount")      int pageCount,
        @JsonProperty("sizeBytes")      long sizeBytes,
        @JsonProperty("presignedPdfUrl") String presignedPdfUrl,
        @JsonProperty("metadata")       @Nullable RenderMetadata metadata
        // … remaining fields per spec §4
) { }
```

PDF download lives on the `Documents` / `DocumentsAsync` facade, not on the descriptor:

```java
byte[] pdf = client.documents().downloadPdf(descriptor);
CompletableFuture<byte[]> pdfFut = client.documentsAsync().downloadPdf(descriptor);
```

Reasoning:

- Records are value types — hiding a `ThreadLocal<HttpClient>` (or any other owner reference) inside one breaks across thread-pool hand-offs, `CompletableFuture` continuations, virtual threads, and any cross-process round-trip (webhook payload, queue message, cached descriptor). Moving the verb onto the facade keeps the descriptor pure and the ownership explicit. AWS SDK v2 uses the same convention (`s3.getObject(request)` rather than `request.execute()`).
- Consumers who deserialize a descriptor from JSON they received out-of-band (webhook, queue) can still call `client.documents().downloadPdf(desc)` against any `PoliPageClient` they own — there is no hidden binding.
- The diverges-from-Node note: Node's `attachDownloadPdf` mutates the returned object with a closure capturing the SDK's `fetch`. The behaviour parity (a `downloadPdf` operation reachable from a descriptor) is preserved; only the surface differs to match JVM idioms.

**Retry policy for downloadPdf**: the presigned S3 fetch is **not** retried by `RetryLoop` — the URL has a short TTL and on failure the right move is to re-`render` (which produces a fresh signed URL), not to bang on a possibly-expired one. `IOException`/`HttpTimeoutException` from the presigned fetch surface as `PoliPageDownloadException` directly. This matches the Node SDK.

---

## 4. Wire serialization (Jackson)

Per spec §6:

- Records map cleanly to JSON via `jackson-databind`'s record support (Jackson 2.12+).
- Configure `ObjectMapper`:
  ```java
  ObjectMapper mapper = JsonMapper.builder()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
      .serializationInclusion(Include.NON_ABSENT)        // omit null and Optional.empty()
      .propertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE) // default, explicit for clarity
      .build();
  ```
  No `JavaTimeModule` / `jackson-datatype-jsr310` dependency in v1.0 — the wire types are `String`, `int`, `long`, `Map<String, Object>`. If a future field types as `Instant`/`OffsetDateTime`, add the module and dependency in the same commit.
- Nullable wire fields use `@Nullable` (JSpecify) on record components, plus `Optional<T>` on read-only accessors when the consumer benefits from forcing a null check.
- `RenderMetadata` is `Map<String, Object>` with primitive values only — enforced at validation time inside the facade methods, matching Node's `assertPrimitiveMetadata`.

### 4.1 Why Jackson and not the BCL?

The JDK has no built-in JSON serializer. Jackson is the de-facto standard across Spring Boot, Quarkus, Micronaut — picking it means consumers' existing `ObjectMapper` configuration coexists without conflict. The alternatives (Gson, Moshi, JSON-B) all add friction in at least one mainstream framework.

---

## 5. Retry orchestration

Per spec §7 and Node's `internal/http.ts`:

| Field | Value |
|---|---|
| Default `maxRetries` | `2` (on top of the initial attempt) |
| Default `retryDelay` | `Duration.ofMillis(500)` |
| Retryable statuses | `5xx`, `429` |
| Retryable exceptions | `IOException` (from `HttpClient.send`), `HttpTimeoutException`, `ConnectException` |
| Backoff | `retryDelay × 2^attempt` with jitter in `[0.5, 1.5)` (uniform random) |
| Jitter | `ThreadLocalRandom.current().nextDouble(0.5, 1.5)` — thread-safe |
| `Retry-After` cap | 30s; honour both seconds and HTTP-date formats |
| Caller cancellation | Never retry. Surface `InterruptedException` / `CancellationException` directly. |

Implementation: `RetryLoop` exposes two methods:

```java
<T> T executeSync(Supplier<HttpResponse<byte[]>> attempt, Function<HttpResponse<byte[]>, T> mapResult)
    throws IOException, InterruptedException;

<T> CompletableFuture<T> executeAsync(Supplier<CompletableFuture<HttpResponse<byte[]>>> attempt,
    Function<HttpResponse<byte[]>, T> mapResult);
```

Both share the `Backoff` calculator and the retryable-error classifier — the only divergence is `Thread.sleep` (sync) vs `CompletableFuture.delayedExecutor` (async).

Per-call: `RequestOptions.idempotencyKey(...)` overrides the auto-generated UUID. The auto-generated key is `UUID.randomUUID().toString()`.

---

## 6. Exception hierarchy

Per spec §8 plus the Node SDK's predicate semantics:

```java
public sealed class PoliPageException extends RuntimeException
        permits PoliPageAuthException,
                PoliPageNotFoundException,
                PoliPageGoneException,
                PoliPageValidationException,
                PoliPageRateLimitException,
                PoliPagePaymentRequiredException,
                PoliPageNetworkException,
                PoliPageDownloadException {

    private final String code;
    private final int    statusCode;
    private final @Nullable String requestId;

    protected PoliPageException(String code, int status, String message, @Nullable String requestId, @Nullable Throwable cause) {
        super(message, cause);
        this.code       = code;
        this.statusCode = status;
        this.requestId  = requestId;
    }

    public String code()             { return code; }
    public int    statusCode()       { return statusCode; }
    public @Nullable String requestId() { return requestId; }
}
```

The base is `sealed`; subclasses are `final` (closed for extension). The sealed hierarchy lets consumers pattern-match exhaustively on the JEP-441 switch:

```java
switch (ex) {
    case PoliPageAuthException auth         -> refreshCredentials();
    case PoliPageRateLimitException rl      -> backoffFor(rl.retryAfter());
    case PoliPageValidationException v      -> surfaceToUser(v.getMessage());
    case PoliPageNotFoundException nf       -> handleMissing();
    case PoliPageGoneException g            -> handleGone();
    case PoliPagePaymentRequiredException p -> upsell();
    case PoliPageNetworkException net       -> retryAtCallSite();
    case PoliPageDownloadException dl       -> reRender();
}
```

Subclasses:
- `PoliPageAuthException` — 401 + 403
- `PoliPageNotFoundException` — 404
- `PoliPageGoneException` — 410
- `PoliPageValidationException` — 400 + 422
- `PoliPageRateLimitException` — 429 with `Duration retryAfter()`
- `PoliPagePaymentRequiredException` — 402
- `PoliPageNetworkException` — wraps `IOException`, sockets, TLS, timeouts
- `PoliPageDownloadException` — presigned S3 fetch failed

`ErrorParsing` reads the wire JSON envelope (`{ code, message, requestId }`) and instantiates the matching subclass. The facade methods wrap `IOException` from `HttpClient.send(...)` into `PoliPageNetworkException`.

**Why unchecked?** Java's checked-exception model would force every call site to declare or catch `PoliPageException`, which is hostile to lambda-heavy code (the async facade returns `CompletableFuture`, which only deals with unchecked exceptions). Modern Java SDKs (Stripe Java, AWS SDK v2, Google Cloud client libraries) all use unchecked exceptions for the same reason.

`PoliPageErrorCode` is a `public final class` with `public static final String UNAUTHORIZED = "UNAUTHORIZED";` constants matching every code in spec §8 — drives `switch (ex.code()) { case PoliPageErrorCode.PAYMENT_REQUIRED -> … }` for fine-grained branching.

---

## 7. Cancellation

### 7.1 Blocking facade

- Per-request timeout via `PoliPageClientOptions.requestTimeout` (default 60s) or per-call `RequestOptions.requestTimeout`. Implemented by setting `HttpRequest.Builder.timeout(duration)` — `java.net.http.HttpClient` enforces it natively.
- When the timeout expires, `HttpClient.send` throws `HttpTimeoutException`. The SDK wraps it into `PoliPageException` with `code() == PoliPageErrorCode.TIMEOUT`. Eligible for retry.
- For thread cancellation, callers interrupt the worker thread. `HttpClient.send` returns `InterruptedException`; the SDK preserves the interrupt status (`Thread.currentThread().interrupt()`) and surfaces a `PoliPageException` with `code() == PoliPageErrorCode.ABORTED`. Never retried.

### 7.2 Async facade

- `client.renderAsync().pdf(input)` returns a `CompletableFuture<byte[]>`. Cancel with `future.cancel(true)`:
  ```java
  CompletableFuture<byte[]> future = client.renderAsync().pdf(input);
  scheduler.schedule(() -> future.cancel(true), 5, TimeUnit.SECONDS);
  ```
- Internally, the async path forwards `cancel(true)` to the `CompletableFuture` returned by `HttpClient.sendAsync(...)`. The JDK's HTTP client honours this since Java 12 by best-effort aborting the in-flight exchange.
- Cancelled futures complete exceptionally with `CancellationException`; the SDK does not wrap this into `PoliPageException`. Never retried.

**Caveat — partial-response leak.** Cancelling mid-stream (after headers, before body completes) may leave the underlying connection half-read; the JDK will discard it from the pool, not return it to a corrupt state, but the in-flight bytes are wasted. Cancelling during body streaming for `render().pdfStream` / `documents().preview` (which return `InputStream`/text bodies) is therefore "safe but not free". Document this on the `*Async` Javadoc so callers don't assume zero-cost cancellation.

### 7.3 Default timeout

60 seconds per attempt, configurable via `PoliPageClientOptions.requestTimeout` or per-call `RequestOptions.requestTimeout`. Distinct from `HttpClient.Builder.connectTimeout(...)`, which controls only TCP connect.

---

## 8. Two facades from one client

The dual-facade design is the JVM-idiomatic way to ship both sync and async surfaces from a single SDK. Modern Java SDKs (e.g. Azure SDK for Java) follow this pattern: same configuration, same behaviour, two surfaces.

```java
PoliPageClient client = PoliPageClient.builder().apiKey("…").build();

// Blocking — return values directly
byte[] pdf = client.render().pdf(input);

// Async — return CompletableFuture<T>
CompletableFuture<byte[]> future = client.renderAsync().pdf(input);
```

Both facades share:
- The same `PoliPageClientOptions` (retry budget, timeout, hooks).
- The same `java.net.http.HttpClient` (one connection pool per `PoliPageClient`).
- The same retry math (`Backoff`).
- The same exception types (async wraps in `CompletionException` per Java convention).

The blocking facade calls `HttpClient.send(...)` (which uses a worker thread); the async facade calls `HttpClient.sendAsync(...)` (which uses the `HttpClient`'s configured executor, defaulting to `ForkJoinPool.commonPool()`).

Callers who only need one surface ignore the other — there is no runtime cost to the unused facade, just two extra methods on `PoliPageClient`.

---

## 9. Logging

`SLF4J` (`org.slf4j.Logger`):

| Level | Trigger |
|---|---|
| `DEBUG` | One per HTTP attempt: method, path, attempt number, idempotency key tail (last 4 chars). |
| `WARN`  | One per retry: status, attempt, delay, reason. |
| `ERROR` | One per terminal failure: code, status, requestId. |

`Authorization` and any header containing `key` (case-insensitive) is scrubbed before logging. Use MDC (`org.slf4j.MDC`) for the request ID so downstream log appenders can include it in their pattern.

**OpenTelemetry**: deferred to v1.1 to keep the v1.0 dependency footprint at SLF4J + Jackson + JSpecify only. Adding `io.opentelemetry:opentelemetry-api` even as `<optional>` would force consumers' module-path users to declare `requires io.opentelemetry.api` regardless of whether they use it. v1.1 will reintroduce instrumentation either as an `optional` dependency or as a separate `page.poli:sdk-opentelemetry` companion artifact — TBD when the Node SDK's OTel story lands and we have a cross-SDK convention to mirror.

---

## 10. Tests

Per the engineering guide §1 and CLAUDE.md §3:

| Layer | Where | Stack |
|---|---|---|
| Unit | `src/test/java/page/poli/sdk/` | JUnit 5 + AssertJ + WireMock |
| Integration | `src/integrationTest/java/page/poli/sdk/` (Maven profile `integration-tests`) | JUnit 5 + AssertJ; gated by `POLI_PAGE_API_KEY` |
| Compile-time | n/a — Java's type system is exercised by `javac` and `errorprone`. |
| Example smoke | `examples/demo/.../Main.java` exercises every public method against the real API. |

### 10.1 Unit test pattern

```java
@Test
void pdf_returns_bytes_on_success() throws Exception {
    WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    server.stubFor(post("/render")
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/pdf")
            .withBody("%PDF-1.4 …".getBytes(StandardCharsets.UTF_8))));

    PoliPageClient client = PoliPageClient.builder()
        .apiKey("pp_test_unit")
        .baseUrl(URI.create(server.baseUrl()))
        .build();

    byte[] pdf = client.render().pdf(ProjectModeInput.builder()
        .project("p").template("t").version("1.0.0").data(Map.of()).build());

    assertThat(pdf).isNotEmpty();
    assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");

    server.stop();
}
```

### 10.2 Coverage target

≥ 90% line coverage on the public surface. JaCoCo (`jacoco-maven-plugin`) feeds Codecov.

---

## 11. CI workflow (`.github/workflows/ci.yml`)

Matches the engineering guide §4:

```yaml
name: CI
on:
  push:
  pull_request:
    branches: [main]

jobs:
  test:
    strategy:
      fail-fast: false
      matrix:
        os: [ubuntu-latest]
        java: ['17', '21', '25']
        include:
          - os: windows-latest
            java: '21'
          - os: macos-latest
            java: '21'
    runs-on: ${{ matrix.os }}
    defaults:
      run:
        shell: bash
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: maven
      - run: ./mvnw -B verify
      - run: ./mvnw -B javadoc:javadoc
      - name: Install smoke
        run: |
          ./mvnw -B -DskipTests install
          mkdir -p /tmp/smoke && cd /tmp/smoke
          cat > pom.xml <<'POM'
          <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>page.poli.smoke</groupId>
              <artifactId>install-smoke</artifactId>
              <version>0</version>
              <properties><maven.compiler.release>17</maven.compiler.release></properties>
              <dependencies>
                  <dependency>
                      <groupId>page.poli</groupId>
                      <artifactId>sdk</artifactId>
                      <version>${SDK_VERSION}</version>
                  </dependency>
              </dependencies>
          </project>
          POM
          mkdir -p src/main/java/smoke
          cat > src/main/java/smoke/Main.java <<'JAVA'
          package smoke;
          import page.poli.sdk.PoliPageClient;
          public class Main {
              public static void main(String[] args) {
                  PoliPageClient c = PoliPageClient.builder().apiKey("pp_test_smoke").build();
                  if (c == null) throw new AssertionError();
              }
          }
          JAVA
          SDK_VERSION=$(cd - >/dev/null && ./mvnw -q -Dexpression=project.version -DforceStdout help:evaluate)
          export SDK_VERSION
          envsubst < pom.xml | sponge pom.xml || sed -i.bak "s/\${SDK_VERSION}/$SDK_VERSION/" pom.xml
          mvn -B -q -e compile exec:java -Dexec.mainClass=smoke.Main
        shell: bash
```

Separate workflows: `integration.yml` (nightly + push-to-main with the `integration-tests` profile), `codeql.yml` (push + pull_request + weekly), `release.yml` (workflow_dispatch with environment gate, GPG signing via OIDC).

The install-smoke step is the engineering guide §4.8 gate: it proves the published JAR — including `module-info.class`, transitive deps, and the resolved `central-publishing-maven-plugin` metadata — is actually consumable by a downstream Maven project. A passing unit-test suite does not prove that.

---

## 12. Release flow

Per engineering guide §6:

1. Bump `<version>` in `pom.xml`.
2. Move `[Unreleased]` to `[X.Y.Z] - YYYY-MM-DD` in `CHANGELOG.md`. Add MIGRATION entry if MAJOR.
3. Commit `chore(release): vX.Y.Z` on `main`.
4. Run `scripts/publish.sh` locally:
   - Pre-flight: assert on `main`, working tree clean, target tag doesn't exist.
   - Verify: `./mvnw -B verify` (unit tests). Run integration tests if `POLI_PAGE_API_KEY` is set.
   - Build + sign + stage: `./mvnw -B deploy -P release -DskipTests` (signs the JARs with GPG and uploads them to the Sonatype Central Portal staging bundle via `central-publishing-maven-plugin`).
   - Inspect: list the staged files.
   - Confirm: prompt before triggering the Central Portal release step.
   - Tag: `git tag vX.Y.Z` locally (don't push).
5. Push the tag manually: `git push origin vX.Y.Z`.
6. Log in to <https://central.sonatype.com>, find the staged repository, close it (validates checksums + signatures), then release it. This is the irreversible step.

The `release.yml` workflow (workflow_dispatch) is the signed-artifact alternative:
- Takes the version as input, refuses to run if it doesn't match `pom.xml`.
- Refuses to run if the tag already exists.
- Runs the same gates as the local script.
- Signs with the `MAVEN_GPG_PRIVATE_KEY` from a deployment environment with required reviewers.
- Uploads to the Sonatype Central Portal staging bundle and (optionally) auto-releases via the `central-publishing-maven-plugin`'s `autoPublish` flag.
- Pushes the tag on success.

---

## 13. Build order — eight phases

| Phase | Deliverable | Goal |
|---|---|---|
| 0 | Repo scaffolding | `pom.xml`, Maven Wrapper, `.editorconfig`, `package-info.java`, CI workflow that auto-skips until phase 1 fills in the manifest. |
| 1 | `PoliPageClient.Builder` + `PoliPageClientOptions` | Validation, default values, internal `HttpClient` build. RED tests for required `apiKey` and base URL. |
| 2 | `render().pdf` happy path | `ProjectModeInput` record, Jackson serialization, `HttpTransport`. WireMock server. |
| 3 | Exception hierarchy + `ErrorParsing` | All non-2xx mapping. Tests for every error code in spec §8. |
| 4 | `RetryLoop` + jitter + `Retry-After` | Tests for backoff math (deterministic via `Random` injection), max attempts, never-retry 4xx. |
| 5 | `render().pdfStream` + `render().preview` + `render().document` | Streaming via `HttpResponse.BodyHandlers.ofInputStream`. The returned `InputStream` owns an HTTP connection — Javadoc, README example, and a unit test all assert `try (InputStream s = client.render().pdfStream(...)) { … }` usage; an unclosed stream leaks a slot from the JDK HTTP/2 pool. The unit test verifies that closing the stream releases the connection (WireMock's `verify(…)` plus a follow-up call that reuses the pool). |
| 6 | `documents().*` facade | `get`, `preview`, `thumbnails`, `delete`. Including `text/html` + `X-Document-Page-Count` parsing. |
| 7 | Async facade (`renderAsync()`, `documentsAsync()`) + examples | `CompletableFuture` wiring, Spring Boot / Quarkus / Micronaut / AWS Lambda examples. |

After Phase 7 the SDK is feature-complete. Ship `1.0.0-RC1`, soak for a week with internal users, then `1.0.0`.

---

## 14. Suggested `pom.xml` skeleton

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>page.poli</groupId>
    <artifactId>sdk</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <name>Poli Page SDK for Java</name>
    <description>Render polished PDFs from HTML templates via the Poli Page API.</description>
    <url>https://github.com/poli-page/sdk-java</url>

    <licenses>
        <license><name>MIT</name><url>https://opensource.org/licenses/MIT</url></license>
    </licenses>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

        <slf4j.version>2.0.13</slf4j.version>
        <jackson.version>2.17.2</jackson.version>
        <jspecify.version>1.0.0</jspecify.version>
    </properties>

    <dependencies>
        <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>${slf4j.version}</version></dependency>
        <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId><version>${jackson.version}</version></dependency>
        <dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId><version>${jspecify.version}</version></dependency>

        <!-- test -->
        <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.10.2</version><scope>test</scope></dependency>
        <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><version>3.26.0</version><scope>test</scope></dependency>
        <dependency><groupId>org.wiremock</groupId><artifactId>wiremock</artifactId><version>3.9.1</version><scope>test</scope></dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
                <configuration>
                    <release>17</release>
                    <compilerArgs>
                        <arg>-Xlint:all,-serial,-processing</arg>
                        <arg>-Werror</arg>
                        <arg>-parameters</arg>
                    </compilerArgs>
                </configuration>
            </plugin>
            <plugin>
                <groupId>com.diffplug.spotless</groupId>
                <artifactId>spotless-maven-plugin</artifactId>
                <version>2.43.0</version>
                <configuration>
                    <java>
                        <googleJavaFormat><version>1.22.0</version></googleJavaFormat>
                    </java>
                </configuration>
                <executions>
                    <execution>
                        <goals><goal>check</goal></goals>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.jacoco</groupId>
                <artifactId>jacoco-maven-plugin</artifactId>
                <version>0.8.12</version>
                <executions>
                    <execution>
                        <id>prepare-agent</id>
                        <goals><goal>prepare-agent</goal></goals>
                    </execution>
                    <execution>
                        <id>report</id>
                        <phase>test</phase>
                        <goals><goal>report</goal></goals>
                    </execution>
                    <execution>
                        <id>check</id>
                        <phase>verify</phase>
                        <goals><goal>check</goal></goals>
                        <configuration>
                            <rules>
                                <rule>
                                    <element>BUNDLE</element>
                                    <limits>
                                        <limit>
                                            <counter>LINE</counter>
                                            <value>COVEREDRATIO</value>
                                            <minimum>0.90</minimum>
                                        </limit>
                                    </limits>
                                    <excludes>
                                        <exclude>page/poli/sdk/internal/Version</exclude>
                                    </excludes>
                                </rule>
                            </rules>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>

    <profiles>
        <profile>
            <id>release</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-source-plugin</artifactId>
                        <version>3.3.1</version>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-javadoc-plugin</artifactId>
                        <version>3.7.0</version>
                    </plugin>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-gpg-plugin</artifactId>
                        <version>3.2.4</version>
                    </plugin>
                    <plugin>
                        <groupId>org.sonatype.central</groupId>
                        <artifactId>central-publishing-maven-plugin</artifactId>
                        <version>0.5.0</version>
                        <extensions>true</extensions>
                        <configuration>
                            <publishingServerId>central</publishingServerId>
                            <autoPublish>false</autoPublish>
                        </configuration>
                    </plugin>
                    <plugin>
                        <!-- Compares the public API of this build against the previously released
                             page.poli:sdk artifact on Maven Central. Fails the build on any binary or
                             source incompatibility (removed/renamed methods, narrowed return types,
                             new abstract methods, etc.) so that breaking changes can only ship behind
                             an explicit MAJOR bump. -->
                        <groupId>com.github.siom79.japicmp</groupId>
                        <artifactId>japicmp-maven-plugin</artifactId>
                        <version>0.23.1</version>
                        <executions>
                            <execution>
                                <phase>verify</phase>
                                <goals><goal>cmp</goal></goals>
                            </execution>
                        </executions>
                        <configuration>
                            <oldVersion>
                                <dependency>
                                    <groupId>page.poli</groupId>
                                    <artifactId>sdk</artifactId>
                                    <version>RELEASE</version>
                                </dependency>
                            </oldVersion>
                            <parameter>
                                <onlyModified>true</onlyModified>
                                <breakBuildOnBinaryIncompatibleModifications>true</breakBuildOnBinaryIncompatibleModifications>
                                <breakBuildOnSourceIncompatibleModifications>true</breakBuildOnSourceIncompatibleModifications>
                                <includes>
                                    <include>page.poli.sdk</include>
                                    <include>page.poli.sdk.input</include>
                                    <include>page.poli.sdk.model</include>
                                    <include>page.poli.sdk.exception</include>
                                </includes>
                            </parameter>
                        </configuration>
                    </plugin>
                </plugins>
            </build>
        </profile>
        <profile>
            <id>integration-tests</id>
            <build>
                <plugins>
                    <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-failsafe-plugin</artifactId>
                        <version>3.2.5</version>
                        <executions>
                            <execution>
                                <goals><goal>integration-test</goal><goal>verify</goal></goals>
                            </execution>
                        </executions>
                    </plugin>
                </plugins>
            </build>
        </profile>
    </profiles>
</project>
```

---

## 15. Observability hooks

Per spec §9.5 and Node's `onRetry` / `onError` shape:

```java
public record RetryEvent(int attempt, Duration delay, Integer statusCode, String reason) { }

PoliPageClient.builder()
    .onRetry(event -> metrics.counter("poli.retry")
        .tag("attempt", String.valueOf(event.attempt()))
        .increment())
    .onError(error -> sentry.captureException(error))
    .build();
```

Hooks are synchronous. The retry hook fires **before** `Thread.sleep`/`CompletableFuture.delayedExecutor`. The error hook fires **before** the exception is thrown (sync) or completed-exceptionally (async). Both are wrapped in `try { … } catch (Throwable t) { /* swallow + log */ }` so a faulty hook never breaks the request.

---

## 16. Module system (JPMS)

Open question — see §18. Decision: ship a `module-info.java` declaring `page.poli.sdk` so consumers on the module path get proper encapsulation, while still working on the classpath for older builds. This is the AWS SDK v2 and Jackson approach.

```java
module page.poli.sdk {
    requires org.slf4j;
    requires com.fasterxml.jackson.core;
    requires com.fasterxml.jackson.annotation;
    requires com.fasterxml.jackson.databind;
    requires java.net.http;
    requires static org.jspecify;     // compile-time only

    exports page.poli.sdk;
    exports page.poli.sdk.input;
    exports page.poli.sdk.model;
    exports page.poli.sdk.exception;
    // page.poli.sdk.internal is NOT exported

    // Allow Jackson to reflect on records in our exported packages.
    opens page.poli.sdk.input    to com.fasterxml.jackson.databind;
    opens page.poli.sdk.model    to com.fasterxml.jackson.databind;
}
```

`requires` lists every Jackson module name the SDK touches at compile time (`core` for `JsonProcessingException`, `annotation` for `@JsonProperty`, `databind` for `ObjectMapper`). The `opens … to com.fasterxml.jackson.databind` lines are required for record deserialization on the module path — without them Jackson's `RecordsHelpers` can't read the canonical constructor.

---

## 17. Differences from sdk-csharp

Most of the architecture is shared with the C# SDK (statically-typed enterprise SDK, sync + async surfaces, immutable client, exception hierarchy). The deltas:

| Concern | .NET | Java |
|---|---|---|
| Client construction | `new PoliPageClient(PoliPageClientOptions)` (init-only records) | `PoliPageClient.builder()…build()` (telescoping ctors don't scale on JVM) |
| Sync/async split | Single async surface (`*Async`); blocking call sites use `.GetAwaiter().GetResult()` | Two facades on the same client (`client.render()` blocking, `client.renderAsync()` returning `CompletableFuture`) — the JVM convention |
| Cancellation | `CancellationToken` parameter on every method | Thread interruption (blocking) + `CompletableFuture.cancel(true)` (async) |
| DI integration | `services.AddPoliPage(...)` extension | Spring / Quarkus / Micronaut integrations are downstream (the SDK exposes plain beans they consume) |
| Logging | `Microsoft.Extensions.Logging.ILogger<T>` | SLF4J `org.slf4j.Logger` |
| Wire serialization | `System.Text.Json` (+ source generation in v1.1) | Jackson (de-facto JVM standard) |
| File helper | `await PoliPageClient.RenderToFileAsync(input, path, …)` | `PoliPageClient.renderToFile(input, path)` + `renderToFileAsync(...)` |
| Release artifact | `.nupkg` push to NuGet | `.jar` + sources + javadoc, GPG-signed, deployed to Maven Central |
| Module system | n/a | JPMS `module-info.java` |
| AOT story | source-generated JSON + Native AOT publishing | GraalVM Native Image + Jackson's reflection-config descriptors |

The Node SDK remains the canonical reference for ambiguous wire-level behaviour.

---

## 18. Open questions

Decisions already taken (kept here for traceability):

- **`DocumentDescriptor` design** — Resolved: descriptor is a pure value record; `downloadPdf(descriptor)` lives on the `Documents` / `DocumentsAsync` facade. See §3.4. The earlier `ThreadLocal<HttpClient>` design was discarded as unsafe across thread-pool hand-offs and cross-process round-trips.
- **JPMS `module-info.java`** — Resolved: ship it (AWS SDK v2 precedent; works on both module path and classpath). See §16 for the declaration.
- **`Optional<T>` on accessors** — Resolved: keep nullable record components annotated `@Nullable`; consumers wrap in `Optional.ofNullable(...)` at the call site. Records can't expose `Optional<T>` accessors without losing auto-generated equality, and Effective Java item 55 advises against `Optional` fields anyway.
- **OpenTelemetry hooks** — Resolved: deferred to v1.1. v1.0 ships SLF4J + the `onRetry`/`onError` callbacks only, so the dependency footprint stays SLF4J + Jackson + JSpecify. See §9.

Genuinely open (need a decision before the relevant phase):

1. **Kotlin coroutine bridge?** Kotlin consumers can use `CompletableFuture` directly via `kotlinx.coroutines.future.await()` — no Kotlin-specific artifact required for v1.0. If demand emerges post-1.0, ship a `page.poli:sdk-kotlin` companion adding `suspend` overloads. Open question: do we want to publish even an empty README placeholder on the kotlin artifact ID so it's reserved on Central?
2. **GraalVM Native Image support?** Jackson needs reflection-config metadata for AOT compilation. Options: (a) ship hand-curated `META-INF/native-image/page.poli/sdk/reflect-config.json` in v1.0; (b) annotate records with `@RegisterForReflection` style hints and let consumers run the Native Image agent; (c) defer entirely to a v1.1 native profile. Recommendation: (a) — Quarkus and Micronaut Native users hit this on day one if the metadata is missing.
3. **Spring Boot / Quarkus / Micronaut integration packages?** These ecosystems have strong DI conventions. Open whether to ship one starter each (`sdk-spring-boot-starter`, `sdk-quarkus-extension`, `sdk-micronaut`) or let consumers wire the `PoliPageClient` bean themselves. Recommendation: defer to a post-1.0 phase, watch adoption signals.
4. **Virtual-threads executor as the async default on Java 21+?** `HttpClient.sendAsync` defaults to `ForkJoinPool.commonPool()`; for high-throughput async workloads `Executor.newVirtualThreadPerTaskExecutor()` is a better fit, but only on JDK 21+. Options: (a) keep the default and document the override; (b) detect the runtime version and switch automatically when on 21+. Recommendation: (a) — automatic feature detection inside an SDK is the kind of magic that surprises consumers.

When in doubt, the rule from `sdk-specification.md` §10 applies: open a discussion in `poli-page/sdk-node` or with Xavier directly. Public-API decisions ripple across the fleet; don't make them solo.
