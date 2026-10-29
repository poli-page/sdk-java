// Extractor entry point for the Poli Page Java SDK docs site.
//
// Why this is a TypeScript script instead of a Java tool (javadoc / Doclet /
// JavaParser):
//
// The reference contract (see the SDK docs convention spec) defines the
// *output* shape, not the implementation. The Node SDK runs TypeDoc and
// transforms its JSON; for Java we'd run javadoc and parse XML/HTML, or
// embed a Doclet, or run JavaParser through Maven. All three add a build
// step that needs the JDK at docs-build time and complicates the GitHub
// Pages CI matrix.
//
// Instead, this script keeps a hand-maintained declarative METHODS list
// (signatures, parameters, error codes) and emits the canonical MDX
// shape — same approach the Node extractor's method-pages.ts uses for the
// per-method METHODS array, except here it's the only source of truth.
//
// The trade-off: when a method signature changes, you also update this
// file. The signature lint in CI (japicmp) already gates API drift in
// `src/main/java`, so the surface evolves deliberately. This file moves
// in the same PR.
//
// The Example block on every method page is read verbatim from
// `examples/<slug>.java` — the same file that's part of the SDK's
// canonical examples. No drift between docs and runnable code.
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..', '..');
const EXAMPLES_DIR = resolve(REPO_ROOT, 'examples');
const REFERENCE_OUT = resolve(REPO_ROOT, 'docs', 'src', 'content', 'docs', 'reference');

interface Param {
  readonly name: string;
  readonly type: string;
  readonly required: boolean;
  readonly description: string;
}

interface Method {
  readonly slug: string;
  readonly displayName: string; // e.g. "render().pdf"
  readonly signature: string;   // canonical Java signature
  readonly summary: string;     // lede; first sentence used for description
  readonly params: readonly Param[];
  readonly returns: string;
  readonly errorCodes: readonly string[];
  readonly exampleFile: string; // file name in examples/
}

// Per-method error codes: every code the method can raise, per the API
// specification's errors of its route.
//
// Auth codes of every API call. The builder only rejects a blank key, so a key
// without the `pp_` prefix reaches the API and gets MISSING_API_KEY; a purged
// organization refuses every request.
const AUTH_ERRORS = ['MISSING_API_KEY', 'INVALID_API_KEY', 'ORGANIZATION_PURGED'];
// Org-state gates the API applies to every call that is not a GET.
const WRITE_GATE_ERRORS = ['ORGANIZATION_PAYMENT_GRACE', 'ORGANIZATION_CANCELLED', 'ORGANIZATION_MIGRATING'];
// Server and transport codes of every API call. `unknown_error` is an error
// body without a code; `aborted` is a caller interrupt.
const SERVER_ERRORS = ['STORAGE_ACCESS_DENIED', 'INTERNAL_ERROR', 'unknown_error', 'timeout', 'network_error', 'aborted'];
// POST /v1/render. The per-credential rate limiter is mounted on /v1/render*
// only. MISSING_DATA, MISSING_TEMPLATE_SLUG, PROJECT_REQUIRED_FOR_DOCUMENT and
// ENVIRONMENT_MISMATCH are left out: RenderInput always sends a non-blank
// project and template and a data object, and never an environment.
const RENDER_DOCUMENT_ERRORS = [
  ...AUTH_ERRORS, ...WRITE_GATE_ERRORS,
  'RATE_LIMIT_EXCEEDED', 'VALIDATION_ERROR', 'RENDER_BODY_TOO_LARGE',
  'PROJECT_NOT_FOUND', 'PROJECT_NOT_IN_ENVIRONMENT', 'TEMPLATE_NOT_FOUND',
  'TEMPLATE_ERROR', 'TEMPLATE_UNSAFE', 'EMPTY_DOCUMENT', 'DOCUMENT_TOO_LARGE', 'RENDER_TIMEOUT',
  'QUOTA_EXCEEDED', 'OVERAGE_CAP_EXCEEDED', 'PUBLISH_IN_PROGRESS',
  ...SERVER_ERRORS, 'invalid_response',
];
// The render, then the presigned-URL download: DOWNLOAD_FAILED for a non-2xx
// answer, no answer or a body cut mid-way; aborted is already listed.
const RENDER_PDF_ERRORS = [...RENDER_DOCUMENT_ERRORS, 'DOWNLOAD_FAILED'];
// GET /v1/documents/:id and /preview: reads, so no write gate and no rate limiter.
const DOCUMENT_READ_ERRORS = [...AUTH_ERRORS, 'DOCUMENT_NOT_FOUND', 'FORBIDDEN', 'DOCUMENT_GONE', ...SERVER_ERRORS];

const METHODS: readonly Method[] = [
  {
    slug: 'render-pdf',
    displayName: 'render().pdf',
    signature: 'byte[] pdf(RenderInput input)',
    summary: 'Render the given input as a PDF and return the raw bytes. Two HTTP hops under the hood: POST /v1/render to produce a stored document, then GET presignedPdfUrl to fetch the bytes.',
    params: [{ name: 'input', type: 'RenderInput', required: true, description: 'The render input — non-null.' }],
    returns: '`byte[]` — the rendered PDF bytes.',
    errorCodes: RENDER_PDF_ERRORS,
    exampleFile: 'render-pdf.java',
  },
  {
    slug: 'render-pdf-stream',
    displayName: 'render().pdfStream',
    signature: 'InputStream pdfStream(RenderInput input)',
    summary: 'Render the given input as a PDF and return a streaming body. Use this for large documents to avoid buffering the full byte array in memory. The returned InputStream owns the underlying HTTP connection — wrap the call in try-with-resources.',
    params: [{ name: 'input', type: 'RenderInput', required: true, description: 'The render input — non-null.' }],
    returns: '`InputStream` reading the PDF body — caller must close.',
    errorCodes: RENDER_PDF_ERRORS,
    exampleFile: 'render-pdf-stream.java',
  },
  {
    slug: 'render-document',
    displayName: 'render().document',
    signature: 'DocumentDescriptor document(RenderInput input)',
    summary: 'Render the given input and return the stored document descriptor without downloading the PDF. Useful when you want to persist documentId and download on demand later.',
    params: [{ name: 'input', type: 'RenderInput', required: true, description: 'The render input — non-null.' }],
    returns: '`DocumentDescriptor` — includes documentId, presignedPdfUrl (15-minute TTL), metadata.',
    errorCodes: RENDER_DOCUMENT_ERRORS,
    exampleFile: 'render-document.java',
  },
  {
    slug: 'documents-get',
    displayName: 'documents().get',
    signature: 'DocumentDescriptor get(String id)',
    summary: 'Retrieve a stored document descriptor with a fresh presignedPdfUrl.',
    params: [{ name: 'id', type: 'String', required: true, description: 'The document ID — non-null, non-blank.' }],
    returns: '`DocumentDescriptor` — with a freshly signed presignedPdfUrl.',
    errorCodes: [...DOCUMENT_READ_ERRORS, 'invalid_response'],
    exampleFile: 'documents-get.java',
  },
  {
    slug: 'documents-preview',
    displayName: 'documents().preview',
    signature: 'DocumentPreviewResult preview(String id)',
    summary: 'Fetch the stored document\'s HTML preview body and page count.',
    params: [{ name: 'id', type: 'String', required: true, description: 'The document ID.' }],
    returns: '`DocumentPreviewResult` — html plus pageCount (from the X-Document-Page-Count header).',
    errorCodes: DOCUMENT_READ_ERRORS,
    exampleFile: 'documents-preview.java',
  },
  {
    slug: 'documents-thumbnails',
    displayName: 'documents().thumbnails',
    signature: 'List<Thumbnail> thumbnails(String id, ThumbnailOptions options)',
    summary: 'Request page thumbnails of the stored document. Every option is optional; thumbnails(id) uses the API defaults (400 px wide jpeg, every page).',
    params: [
      { name: 'id', type: 'String', required: true, description: 'The document ID.' },
      { name: 'options', type: 'ThumbnailOptions', required: false, description: 'Optional width, format (jpeg, webp, png), quality (jpeg only) and page filter. Unset options are not sent.' },
    ],
    returns: '`List<Thumbnail>` — base64-encoded images, one per requested page.',
    errorCodes: [
      ...AUTH_ERRORS, ...WRITE_GATE_ERRORS,
      'DOCUMENT_NOT_FOUND', 'FORBIDDEN', 'DOCUMENT_GONE', 'THUMBNAILS_NOT_AVAILABLE',
      'VALIDATION_ERROR', 'QUOTA_EXCEEDED', 'OVERAGE_CAP_EXCEEDED',
      ...SERVER_ERRORS, 'invalid_response',
    ],
    exampleFile: 'documents-thumbnails.java',
  },
  {
    slug: 'documents-delete',
    displayName: 'documents().delete',
    signature: 'void delete(String id)',
    summary: 'Soft-delete a stored document. Idempotent: deleting an already-deleted document also succeeds (204). Subsequent get / preview calls throw PoliPageGoneException (HTTP 410, DOCUMENT_GONE).',
    params: [{ name: 'id', type: 'String', required: true, description: 'The document ID.' }],
    returns: '`void` — no body on success.',
    errorCodes: [
      ...AUTH_ERRORS, ...WRITE_GATE_ERRORS,
      'DOCUMENT_NOT_FOUND', 'FORBIDDEN', 'ENVIRONMENT_PROTECTED', ...SERVER_ERRORS,
    ],
    exampleFile: 'documents-delete.java',
  },
  {
    slug: 'render-to-file',
    displayName: 'renderToFile',
    signature: 'void renderToFile(RenderInput input, Path path)',
    summary: 'Render the input as a PDF and stream the bytes directly to path, overwriting any existing file. Convenience over render().pdfStream + manual copy.',
    params: [
      { name: 'input', type: 'RenderInput', required: true, description: 'The render input — non-null.' },
      { name: 'path', type: 'Path', required: true, description: 'Destination file path — non-null. Existing file is overwritten.' },
    ],
    returns: '`void` — the file is closed before the method returns, even on failure.',
    errorCodes: [...RENDER_PDF_ERRORS, 'io_error'],
    exampleFile: 'render-to-file.java',
  },
];

const SDK_INTERNAL_ERRORS = [
  { code: 'invalid_options', when: 'Builder validation failed (a missing or blank apiKey gives `apiKey is required`, a non-absolute baseUrl, etc.). Thrown synchronously by PoliPageClient.Builder.build().' },
  { code: 'network_error', when: 'TCP/TLS-layer failure reaching the API, or a connection closed before or during the answer. Message `Network request failed`. Retryable.' },
  { code: 'timeout', when: 'An attempt, body included, did not complete within the configured requestTimeout. Message `Request timed out after <ms>ms`. Retryable.' },
  { code: 'aborted', when: 'Caller interrupted a blocking call. Message `Request was aborted`. A cancelled CompletableFuture completes with CancellationException instead. Not retryable.' },
  { code: 'DOWNLOAD_FAILED', when: 'The presigned PDF URL download failed: `Failed to download PDF: HTTP <status>` with the storage status (typically 403 for an expired URL), or `Failed to download PDF: network error` with no status. Not retryable.' },
  { code: 'io_error', when: 'Local file write failed inside renderToFile.' },
  { code: 'invalid_response', when: 'The API returned a 2xx response whose body could not be parsed as the expected type.' },
  { code: 'unknown_error', when: 'The API returned an error body with neither a code nor an error field. The HTTP status is kept.' },
];

const API_ERRORS = [
  { code: 'MISSING_API_KEY', when: 'No Bearer pp_* API key in the request, which includes a key without the pp_ prefix. HTTP 401.' },
  { code: 'INVALID_API_KEY', when: 'The API key is malformed or revoked. HTTP 401.' },
  { code: 'FORBIDDEN', when: 'The key does not have access to the requested resource. HTTP 403.' },
  { code: 'ENVIRONMENT_PROTECTED', when: 'The target environment is protected. HTTP 403.' },
  { code: 'THUMBNAILS_NOT_AVAILABLE', when: 'Thumbnails are not available for this organization. HTTP 403.' },
  { code: 'ORGANIZATION_PAYMENT_GRACE', when: 'The organization is in its payment grace period. HTTP 403.' },
  { code: 'ORGANIZATION_CANCELLED', when: 'The organization has been cancelled. HTTP 403.' },
  { code: 'ORGANIZATION_PURGED', when: 'The organization has been purged. HTTP 410.' },
  { code: 'ORGANIZATION_MIGRATING', when: 'The organization is being migrated. HTTP 503.' },
  { code: 'NOT_FOUND', when: 'The resource does not exist. HTTP 404.' },
  { code: 'PROJECT_NOT_FOUND', when: 'The project does not exist. HTTP 404.' },
  { code: 'PROJECT_NOT_IN_ENVIRONMENT', when: 'The project has no copy in the key\'s environment. HTTP 404.' },
  { code: 'TEMPLATE_NOT_FOUND', when: 'The template does not exist in the project. HTTP 404.' },
  { code: 'DOCUMENT_NOT_FOUND', when: 'No stored document matches the supplied id. HTTP 404.' },
  { code: 'DOCUMENT_GONE', when: 'The document existed but has been deleted. HTTP 410.' },
  { code: 'VALIDATION_ERROR', when: 'The request body failed validation (unknown field, out-of-range option, …). HTTP 400.' },
  { code: 'MISSING_DATA', when: 'Request body lacks the required `data` field. HTTP 400.' },
  { code: 'MISSING_TEMPLATE_SLUG', when: 'Template slug is missing. HTTP 400.' },
  { code: 'PROJECT_REQUIRED_FOR_DOCUMENT', when: 'A document render needs a `project`. HTTP 400.' },
  { code: 'ENVIRONMENT_MISMATCH', when: 'The request does not match the key\'s environment. HTTP 400.' },
  { code: 'EMPTY_DOCUMENT', when: 'The rendered document is empty. HTTP 400.' },
  { code: 'TEMPLATE_ERROR', when: 'The template could not be rendered. HTTP 400.' },
  { code: 'TEMPLATE_UNSAFE', when: 'The template, a partial or an SVG asset holds an injection attempt; the message names it. HTTP 400. Not retryable.' },
  { code: 'RENDER_BODY_TOO_LARGE', when: 'The render request body is too large. HTTP 413.' },
  { code: 'DOCUMENT_TOO_LARGE', when: 'The rendered document is too large. HTTP 413.' },
  { code: 'QUOTA_EXCEEDED', when: 'Monthly quota reached. HTTP 429. Not retryable: it lasts until the monthly reset.' },
  { code: 'OVERAGE_CAP_EXCEEDED', when: 'Hard overage cap reached. HTTP 429. Not retryable: it lasts until the monthly reset.' },
  { code: 'RATE_LIMIT_EXCEEDED', when: 'Request rate limit exceeded. HTTP 429. Retryable.' },
  { code: 'INTERNAL_ERROR', when: 'The API returned 5xx, or an error body that is not JSON or broke off while read (the real status kept). Retryable on 5xx and 429.' },
  { code: 'PUBLISH_IN_PROGRESS', when: 'A publish locks the project in the key\'s environment. HTTP 503 with Retry-After. Retryable.' },
  { code: 'STORAGE_ACCESS_DENIED', when: 'Storage access was denied. HTTP 502. Not retryable: it lasts until the bucket access is restored.' },
  { code: 'RENDER_TIMEOUT', when: 'The render did not finish in time. HTTP 504. Retryable.' },
];

const TYPES = [
  { name: 'PoliPageClient', kind: 'class', summary: 'Entry point of the SDK. Constructed via PoliPageClient.builder().' },
  { name: 'PoliPageClient.Builder', kind: 'class', summary: 'Fluent builder for PoliPageClient. The only required field is apiKey.' },
  { name: 'Render', kind: 'class', summary: 'Blocking render facade. Obtained via client.render().' },
  { name: 'RenderAsync', kind: 'class', summary: 'Asynchronous counterpart of Render. Each method returns a `CompletableFuture` of the blocking facade\'s return type.' },
  { name: 'Documents', kind: 'class', summary: 'Blocking stored-documents facade. Obtained via client.documents().' },
  { name: 'DocumentsAsync', kind: 'class', summary: 'Asynchronous counterpart of Documents.' },
  { name: 'RenderInput', kind: 'record', summary: 'Render against a stored project + template by slug, using the copy published in the API key\'s environment. project, template, data required; metadata, format, orientation, locale optional. Constructed via builder().' },
  { name: 'DocumentDescriptor', kind: 'record', summary: 'Stored document descriptor returned by render().document and documents().get. Includes documentId, environment (any environment name), presignedPdfUrl (15-minute TTL), metadata.' },
  { name: 'DocumentPreviewResult', kind: 'record', summary: 'Result of documents().preview. Carries html and pageCount.' },
  { name: 'Thumbnail', kind: 'record', summary: 'One page thumbnail: page (1-based), width, height, contentType, base64-encoded data.' },
  { name: 'ThumbnailOptions', kind: 'record', summary: 'Options for documents().thumbnails. Every option is optional (API defaults: 400 px wide jpeg); quality applies to jpeg only; pages restricts rendering. No client-side bounds check.' },
  { name: 'ThumbnailFormat', kind: 'enum', summary: 'PNG, JPEG or WEBP. Wire form is lowercase.' },
  { name: 'RetryEvent', kind: 'record', summary: 'Event passed to the onRetry hook before each retry sleep. attempt, delay, statusCode, reason.' },
  { name: 'PoliPageErrorCode', kind: 'class', summary: 'String constants for the known API error codes plus SDK-internal codes. The code is an open set: an unknown code reaches you unchanged.' },
];

function readVersionFromPom(): string {
  const pom = readFileSync(join(REPO_ROOT, 'pom.xml'), 'utf8');
  const match = pom.match(/<version>([^<]+)<\/version>/);
  if (!match) throw new Error('extractor: could not find <version> in pom.xml');
  return match[1] ?? '0.0.0';
}

function firstSentence(s: string): string {
  const trimmed = s.trim().replace(/\s+/g, ' ');
  const idx = trimmed.indexOf('. ');
  return idx > 0 ? trimmed.slice(0, idx + 1) : trimmed;
}

function escapeFrontmatter(s: string): string {
  return s.replace(/"/g, '\\"').replace(/\n/g, ' ').slice(0, 150);
}

function methodMdx(m: Method): string {
  const example = readFileSync(join(EXAMPLES_DIR, m.exampleFile), 'utf8').trimEnd();
  const description = escapeFrontmatter(firstSentence(m.summary));
  const paramsBlock = m.params.length === 0
    ? ''
    : `\n## Parameters\n\n<ParamsTable params={${JSON.stringify(m.params)}} />\n`;
  const errorsBlock = m.errorCodes.length === 0
    ? ''
    : `\n## Errors\n\n<ErrorTable errors={${JSON.stringify(
        m.errorCodes.map((code) => ({ code, when: 'See [errors](../../../production/errors/) for the full description.' })),
      )}} />\n`;

  return `---
title: ${m.displayName}
description: ${description}
sidebar:
  label: ${m.displayName}
---

import MethodSignature from '@preset/components/MethodSignature.astro';
import ParamsTable from '@preset/components/ParamsTable.astro';
import ErrorTable from '@preset/components/ErrorTable.astro';

<MethodSignature lang="java" code={\`${m.signature}\`} />

${m.summary}

${paramsBlock}
## Returns

${m.returns}
${errorsBlock}
## Example

\`\`\`java
${example}
\`\`\`

## See also
- [Errors](../../../production/errors/)
- [Configuration](../../../concepts/configuration/)
`;
}

function clientMdx(): string {
  return `---
title: Client
description: The PoliPageClient class — the only entry point to the Java SDK.
---

import MethodSignature from '@preset/components/MethodSignature.astro';

<MethodSignature lang="java" code={\`PoliPageClient client = PoliPageClient.builder().apiKey(apiKey).build()\`} />

Entry point of the Poli Page SDK for Java. Instances are immutable and thread-safe — reuse one client per application rather than creating one per request.

## Construction

The client is built via the static \`builder()\` method. The only required field is \`apiKey\`; every other option has a documented default. See [\`Configuration\`](../../concepts/configuration/) for the full list and [\`PoliPageClient.Builder\`](../types/) for every setter.

## Facades

The client exposes four cached facade instances:

- [\`render()\`](./methods/render-pdf/) — blocking render facade (PDF, stream, document).
- [\`renderAsync()\`](./methods/render-pdf/) — asynchronous counterpart; methods return \`CompletableFuture<T>\`.
- [\`documents()\`](./methods/documents-get/) — blocking stored-documents facade (get, preview, thumbnails, delete).
- [\`documentsAsync()\`](./methods/documents-get/) — asynchronous counterpart.

The convenience helpers [\`renderToFile\`](./methods/render-to-file/) and \`renderToFileAsync\` live directly on \`PoliPageClient\`.

## See also
- [Types](../types/)
- [Errors](../errors/)
- [Runtime support](../runtime-support/)
`;
}

function typesMdx(): string {
  const sections = TYPES.map((t) => `### \`${t.name}\` (${t.kind})\n\n${t.summary}\n`).join('\n');
  return `---
title: Types
description: Public types and classes exported from page.poli.sdk.
---

The Java SDK exposes the types below across the \`page.poli.sdk\`, \`page.poli.sdk.input\`, \`page.poli.sdk.model\`, and \`page.poli.sdk.exception\` packages.

${sections}

For exception subclasses (\`PoliPageException\` and the eight final subclasses), see [errors](../errors/). For the full Javadoc, see [the source on GitHub](https://github.com/poli-page/sdk-java/blob/main/src/main/java/page/poli/sdk/).
`;
}

function errorsMdx(): string {
  return `---
title: Errors
description: All error codes raised by PoliPageException, grouped by source.
---

import ErrorTable from '@preset/components/ErrorTable.astro';

Every failure thrown by the SDK is a \`PoliPageException\` (a \`RuntimeException\`). The hierarchy is sealed; pattern-match exhaustively against the eight final subclasses plus the base. SDK-internal codes are lowercase; codes from the API are SCREAMING_SNAKE_CASE.

## Exception subclasses

The sealed permits are: \`PoliPageAuthException\` (401/403), \`PoliPageNotFoundException\` (404), \`PoliPageGoneException\` (410), \`PoliPageValidationException\` (400/422), \`PoliPageRateLimitException\` (429, exposes \`retryAfter()\`), \`PoliPageNetworkException\` (transport, no HTTP status), \`PoliPageDownloadException\` (presigned URL fetch), \`PoliPageInvalidOptionsException\` (client builder validation). The base \`PoliPageException\` is also concrete for the unmapped tail.

## SDK-internal codes

<ErrorTable errors={${JSON.stringify(SDK_INTERNAL_ERRORS)}} />

## API codes

<ErrorTable errors={${JSON.stringify(API_ERRORS)}} />
`;
}

function runtimeSupportMdx(version: string): string {
  return `---
title: Runtime support
description: Supported JVM versions and operating systems for page.poli:sdk v${version}.
---

import RuntimeMatrix from '@preset/components/RuntimeMatrix.astro';

The Java SDK targets Java 17 LTS and is built and tested against the matrix below.

<RuntimeMatrix matrix={{
  runtimes: ['17', '21', '25'],
  os: ['linux', 'macos', 'windows'],
  cells: {
    '17': { linux: 'tested', macos: 'supported', windows: 'supported' },
    '21': { linux: 'tested', macos: 'tested', windows: 'tested' },
    '25': { linux: 'tested', macos: 'supported', windows: 'supported' },
  },
}} />

The minimum supported JVM is **Java 17**. The SDK uses sealed interfaces, records, and pattern-matching switch — none of which compile under earlier releases.

## Why these versions

The matrix tracks Java's LTS cadence — Java 17 stays as the floor; Java 21 is the full-OS matrix anchor; Java 25 is the latest LTS.
`;
}

function metaJson(version: string): string {
  const now = new Date().toISOString();
  return JSON.stringify({
    language: 'java',
    package: { kind: 'maven', name: 'page.poli:sdk', version },
    extractedAt: now,
    extractorVersion: '0.1.0',
    client: { name: 'PoliPageClient', kind: 'class' },
    methods: METHODS.map((m) => ({ slug: m.slug, name: m.displayName })),
    errors: [...SDK_INTERNAL_ERRORS, ...API_ERRORS].map((e) => ({ code: e.code })),
  }, null, 2) + '\n';
}

// Every code on a method page must be on the errors page, once.
function assertMethodErrorCodes(): void {
  const documented = new Set([...SDK_INTERNAL_ERRORS, ...API_ERRORS].map((e) => e.code));
  for (const m of METHODS) {
    const undocumented = m.errorCodes.filter((code) => !documented.has(code));
    if (undocumented.length > 0 || new Set(m.errorCodes).size !== m.errorCodes.length) {
      throw new Error(`extractor: ${m.slug} error codes are duplicated or not on the errors page (${undocumented.join(', ')})`);
    }
  }
}

function run(): void {
  assertMethodErrorCodes();
  const version = readVersionFromPom();

  // 1. Clear previous output.
  if (existsSync(REFERENCE_OUT)) rmSync(REFERENCE_OUT, { recursive: true, force: true });
  mkdirSync(REFERENCE_OUT, { recursive: true });
  mkdirSync(join(REFERENCE_OUT, 'methods'), { recursive: true });

  // 2. Emit each page.
  writeFileSync(join(REFERENCE_OUT, 'client.mdx'), clientMdx(), 'utf8');
  for (const m of METHODS) {
    writeFileSync(join(REFERENCE_OUT, 'methods', `${m.slug}.mdx`), methodMdx(m), 'utf8');
  }
  writeFileSync(join(REFERENCE_OUT, 'types.mdx'), typesMdx(), 'utf8');
  writeFileSync(join(REFERENCE_OUT, 'errors.mdx'), errorsMdx(), 'utf8');
  writeFileSync(join(REFERENCE_OUT, 'runtime-support.mdx'), runtimeSupportMdx(version), 'utf8');
  writeFileSync(join(REFERENCE_OUT, '_meta.json'), metaJson(version), 'utf8');

  console.log(`extractor: wrote ${REFERENCE_OUT}`);
}

run();
