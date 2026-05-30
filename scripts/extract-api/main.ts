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

const COMMON_RETRYABLE = ['VALIDATION_ERROR', 'NOT_FOUND', 'QUOTA_EXCEEDED', 'timeout', 'network_error', 'INTERNAL_ERROR'];

const METHODS: readonly Method[] = [
  {
    slug: 'render-pdf',
    displayName: 'render().pdf',
    signature: 'byte[] pdf(ProjectModeInput input)',
    summary: 'Render the given input as a PDF and return the raw bytes. Two HTTP hops under the hood: POST /v1/render to produce a stored document, then GET presignedPdfUrl to fetch the bytes.',
    params: [{ name: 'input', type: 'ProjectModeInput', required: true, description: 'The render input — non-null, project mode.' }],
    returns: '`byte[]` — the rendered PDF bytes.',
    errorCodes: COMMON_RETRYABLE,
    exampleFile: 'render-pdf.java',
  },
  {
    slug: 'render-pdf-stream',
    displayName: 'render().pdfStream',
    signature: 'InputStream pdfStream(ProjectModeInput input)',
    summary: 'Render the given input as a PDF and return a streaming body. Use this for large documents to avoid buffering the full byte array in memory. The returned InputStream owns the underlying HTTP connection — wrap the call in try-with-resources.',
    params: [{ name: 'input', type: 'ProjectModeInput', required: true, description: 'The render input — non-null, project mode.' }],
    returns: '`InputStream` reading the PDF body — caller must close.',
    errorCodes: COMMON_RETRYABLE,
    exampleFile: 'render-pdf-stream.java',
  },
  {
    slug: 'render-preview',
    displayName: 'render().preview',
    signature: 'PreviewResult preview(RenderInput input)',
    summary: 'Render the given input as an HTML preview without producing a stored document. Accepts either ProjectModeInput or InlineModeInput via the sealed RenderInput supertype.',
    params: [{ name: 'input', type: 'RenderInput', required: true, description: 'The render input — accepts either ProjectModeInput or InlineModeInput.' }],
    returns: '`PreviewResult` — html, totalPages, environment.',
    errorCodes: ['VALIDATION_ERROR', 'NOT_FOUND', 'QUOTA_EXCEEDED', 'INTERNAL_ERROR'],
    exampleFile: 'render-preview.java',
  },
  {
    slug: 'render-document',
    displayName: 'render().document',
    signature: 'DocumentDescriptor document(ProjectModeInput input)',
    summary: 'Render the given input and return the stored document descriptor without downloading the PDF. Useful when you want to persist documentId and download on demand later.',
    params: [{ name: 'input', type: 'ProjectModeInput', required: true, description: 'The render input — non-null, project mode.' }],
    returns: '`DocumentDescriptor` — includes documentId, presignedPdfUrl (15-minute TTL), metadata.',
    errorCodes: ['VALIDATION_ERROR', 'NOT_FOUND', 'QUOTA_EXCEEDED', 'INTERNAL_ERROR'],
    exampleFile: 'render-document.java',
  },
  {
    slug: 'documents-get',
    displayName: 'documents().get',
    signature: 'DocumentDescriptor get(String id)',
    summary: 'Retrieve a stored document descriptor with a fresh presignedPdfUrl.',
    params: [{ name: 'id', type: 'String', required: true, description: 'The document ID — non-null, non-blank.' }],
    returns: '`DocumentDescriptor` — with a freshly signed presignedPdfUrl.',
    errorCodes: ['DOCUMENT_NOT_FOUND', 'INVALID_API_KEY', 'INTERNAL_ERROR'],
    exampleFile: 'documents-get.java',
  },
  {
    slug: 'documents-preview',
    displayName: 'documents().preview',
    signature: 'DocumentPreviewResult preview(String id)',
    summary: 'Fetch the stored document\'s HTML preview body and page count.',
    params: [{ name: 'id', type: 'String', required: true, description: 'The document ID.' }],
    returns: '`DocumentPreviewResult` — html plus pageCount (from the X-Document-Page-Count header).',
    errorCodes: ['DOCUMENT_NOT_FOUND', 'INVALID_API_KEY', 'INTERNAL_ERROR'],
    exampleFile: 'documents-preview.java',
  },
  {
    slug: 'documents-thumbnails',
    displayName: 'documents().thumbnails',
    signature: 'List<Thumbnail> thumbnails(String id, ThumbnailOptions options)',
    summary: 'Request page thumbnails of the stored document.',
    params: [
      { name: 'id', type: 'String', required: true, description: 'The document ID.' },
      { name: 'options', type: 'ThumbnailOptions', required: true, description: 'Thumbnail width, format, optional quality (JPEG only) and page filter.' },
    ],
    returns: '`List<Thumbnail>` — base64-encoded images, one per requested page.',
    errorCodes: ['DOCUMENT_NOT_FOUND', 'VALIDATION_ERROR', 'INVALID_API_KEY', 'INTERNAL_ERROR'],
    exampleFile: 'documents-thumbnails.java',
  },
  {
    slug: 'documents-delete',
    displayName: 'documents().delete',
    signature: 'void delete(String id)',
    summary: 'Soft-delete a stored document. Subsequent get / preview calls throw PoliPageGoneException (HTTP 410).',
    params: [{ name: 'id', type: 'String', required: true, description: 'The document ID.' }],
    returns: '`void` — no body on success.',
    errorCodes: ['DOCUMENT_NOT_FOUND', 'INVALID_API_KEY', 'INTERNAL_ERROR'],
    exampleFile: 'documents-delete.java',
  },
  {
    slug: 'render-to-file',
    displayName: 'renderToFile',
    signature: 'void renderToFile(ProjectModeInput input, Path path)',
    summary: 'Render the input as a PDF and stream the bytes directly to path, overwriting any existing file. Convenience over render().pdfStream + manual copy.',
    params: [
      { name: 'input', type: 'ProjectModeInput', required: true, description: 'The render input — non-null.' },
      { name: 'path', type: 'Path', required: true, description: 'Destination file path — non-null. Existing file is overwritten.' },
    ],
    returns: '`void` — the file is closed before the method returns, even on failure.',
    errorCodes: [...COMMON_RETRYABLE, 'io_error'],
    exampleFile: 'render-to-file.java',
  },
];

const SDK_INTERNAL_ERRORS = [
  { code: 'invalid_options', when: 'Builder validation failed (missing apiKey, non-absolute baseUrl, etc.). Thrown synchronously by PoliPageClient.Builder.build().' },
  { code: 'network_error', when: 'TCP/TLS-layer failure reaching the API. Retryable.' },
  { code: 'timeout', when: 'The request did not complete within the configured requestTimeout. Retryable.' },
  { code: 'aborted', when: 'Caller interrupted a blocking call or cancelled the CompletableFuture. Not retryable.' },
  { code: 'download_failed', when: 'Presigned PDF URL fetch returned non-2xx (typically expired URL).' },
  { code: 'io_error', when: 'Local file write failed inside renderToFile.' },
  { code: 'invalid_response', when: 'The API returned a 2xx response whose body could not be parsed as the expected type.' },
];

const API_ERRORS = [
  { code: 'MISSING_API_KEY', when: 'No API key in the request.' },
  { code: 'INVALID_API_KEY', when: 'The API key is malformed or revoked.' },
  { code: 'FORBIDDEN', when: 'The key does not have access to the requested resource.' },
  { code: 'PAYMENT_REQUIRED', when: 'Organization billing is past due.' },
  { code: 'ORGANIZATION_CANCELLED', when: 'The organization has been cancelled.' },
  { code: 'ORGANIZATION_PURGED', when: 'The organization has been purged.' },
  { code: 'NOT_FOUND', when: 'The project/template slug does not exist or is not published.' },
  { code: 'VERSION_NOT_FOUND', when: 'The pinned version does not exist for this template.' },
  { code: 'DOCUMENT_NOT_FOUND', when: 'No stored document matches the supplied id.' },
  { code: 'GONE', when: 'The resource existed but has been deleted.' },
  { code: 'VALIDATION_ERROR', when: '`data` does not satisfy the template schema.' },
  { code: 'MISSING_DATA', when: 'Request body lacks the required `data` field.' },
  { code: 'MISSING_PROJECT_OR_TEMPLATE', when: 'Project mode call without both `project` and `template`.' },
  { code: 'MISSING_TEMPLATE_SLUG', when: 'Template slug is missing.' },
  { code: 'INVALID_VERSION_FORMAT', when: 'The `version` string is not a valid semver.' },
  { code: 'VERSION_REQUIRED', when: 'Live keys require a pinned `version`.' },
  { code: 'INVALID_VERSION_FOR_KEY_ENV', when: 'Sandbox key targeting a live-only version, or vice versa.' },
  { code: 'QUOTA_EXCEEDED', when: 'Per-key rate limit or monthly quota reached. Retryable.' },
  { code: 'OVERAGE_CAP_EXCEEDED', when: 'Hard overage cap reached. Not retryable.' },
  { code: 'INTERNAL_ERROR', when: 'The API returned 5xx. Retryable.' },
];

const TYPES = [
  { name: 'PoliPageClient', kind: 'class', summary: 'Entry point of the SDK. Constructed via PoliPageClient.builder().' },
  { name: 'PoliPageClient.Builder', kind: 'class', summary: 'Fluent builder for PoliPageClient. The only required field is apiKey.' },
  { name: 'Render', kind: 'class', summary: 'Blocking render facade. Obtained via client.render().' },
  { name: 'RenderAsync', kind: 'class', summary: 'Asynchronous counterpart of Render. Each method returns a `CompletableFuture` of the blocking facade\'s return type.' },
  { name: 'Documents', kind: 'class', summary: 'Blocking stored-documents facade. Obtained via client.documents().' },
  { name: 'DocumentsAsync', kind: 'class', summary: 'Asynchronous counterpart of Documents.' },
  { name: 'RenderInput', kind: 'sealed interface', summary: 'Sealed supertype permitting ProjectModeInput and InlineModeInput. Used by render().preview.' },
  { name: 'ProjectModeInput', kind: 'record', summary: 'Render against a stored project + template by slug. project, template, data required; version and metadata optional. Constructed via builder().' },
  { name: 'InlineModeInput', kind: 'record', summary: 'Render with raw HTML inline. template and data required; metadata optional. Accepted only by render().preview.' },
  { name: 'DocumentDescriptor', kind: 'record', summary: 'Stored document descriptor returned by render().document and documents().get. Includes documentId, presignedPdfUrl (15-minute TTL), metadata.' },
  { name: 'PreviewResult', kind: 'record', summary: 'Result of render().preview. Carries html, totalPages, environment.' },
  { name: 'DocumentPreviewResult', kind: 'record', summary: 'Result of documents().preview. Carries html and pageCount.' },
  { name: 'Thumbnail', kind: 'record', summary: 'One page thumbnail: page (1-based), width, height, contentType, base64-encoded data.' },
  { name: 'ThumbnailOptions', kind: 'record', summary: 'Options for documents().thumbnails. width and format required; quality only valid for JPEG; pages restricts rendering.' },
  { name: 'ThumbnailFormat', kind: 'enum', summary: 'PNG or JPEG. Wire form is lowercase.' },
  { name: 'RetryEvent', kind: 'record', summary: 'Event passed to the onRetry hook before each retry sleep. attempt, delay, statusCode, reason.' },
  { name: 'PoliPageErrorCode', kind: 'class', summary: 'String constants for every wire error code plus SDK-internal codes.' },
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

- [\`render()\`](./methods/render-pdf/) — blocking render facade (PDF, stream, preview, document).
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

The sealed permits are: \`PoliPageAuthException\` (401/403), \`PoliPagePaymentRequiredException\` (402), \`PoliPageNotFoundException\` (404), \`PoliPageGoneException\` (410), \`PoliPageValidationException\` (400/422), \`PoliPageRateLimitException\` (429, exposes \`retryAfter()\`), \`PoliPageNetworkException\` (transport, no HTTP status), \`PoliPageDownloadException\` (presigned URL fetch). The base \`PoliPageException\` is also concrete for the unmapped tail.

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
  runtimes: ['17', '21', '24'],
  os: ['linux', 'macos', 'windows'],
  cells: {
    '17': { linux: 'tested', macos: 'supported', windows: 'supported' },
    '21': { linux: 'tested', macos: 'tested', windows: 'tested' },
    '24': { linux: 'tested', macos: 'supported', windows: 'supported' },
  },
}} />

The minimum supported JVM is **Java 17**. The SDK uses sealed interfaces, records, and pattern-matching switch — none of which compile under earlier releases.

## Why these versions

The matrix tracks Java's LTS cadence — latest two LTS releases plus the current non-LTS. Java 17 stays as the floor; Java 21 is the full-OS matrix anchor; Java 24 confirms that the next LTS (25) is on track.
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

function run(): void {
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
