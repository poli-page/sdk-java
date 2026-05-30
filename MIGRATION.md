# Migration Guide

This file documents breaking changes between major versions of
`page.poli:sdk`. We follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html):
breaking changes only ship in major version bumps and always come with
an entry here.

## 1.0

The first stable release. Treat `1.0.0` as the starting point for the
Java SDK — there is no prior published surface to migrate from.

### Surface

```java
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.PoliPageClientOptions;
import page.poli.sdk.input.ProjectModeInput;

PoliPageClient client = PoliPageClient.builder()
    .apiKey(System.getenv("POLI_PAGE_API_KEY"))
    .build();

// Render facade (blocking)
//   client.render().pdf, pdfStream, document → project mode only
//                                              (project + template + version)
//   client.render().preview                  → both project and inline mode

// Render facade (async, returns CompletableFuture<T>)
//   client.renderAsync().pdf, pdfStream, preview, document

// Documents facade (stored-document lifecycle)
//   client.documents().get, preview, thumbnails, delete
//   client.documentsAsync().get, preview, thumbnails, delete

// File helper (static method)
//   PoliPageClient.renderToFile(input, path)
```

### Behaviour parity with `@poli-page/sdk@1.0`

`1.0.0` of the Java SDK is behaviour-identical to `@poli-page/sdk@1.0`:
same retry policy (5xx + 429 + network + timeout; jitter `[0.5, 1.5)`;
`Retry-After` cap 30s), same error-code round-tripping, same predicate
exceptions (`PoliPageAuthException` covers 401 + 403), same constructor
validation, same hooks-never-break-the-request semantics, same
project-mode-only constraint on `render().pdf` / `pdfStream` /
`document`, same primitive-only `metadata`, same thumbnails wire
wrap/unwrap, same `documents().preview` `text/html` +
`X-Document-Page-Count` parsing.
