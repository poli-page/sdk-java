/**
 * JPMS module definition for {@code page.poli.sdk}.
 *
 * <p>{@code requires static org.jspecify} marks JSpecify as compile-time only — consumers' module
 * path doesn't need it at runtime. Jackson annotation + core + databind are required because the
 * SDK's public types carry {@code @JsonProperty} and the wire serializer is part of the SDK's
 * contract. {@code opens} directives expose the record packages to Jackson databind for reflective
 * record-constructor access on the module path.
 *
 * <p>The {@code internal} package is intentionally not exported.
 */
module page.poli.sdk {
  requires org.slf4j;
  requires com.fasterxml.jackson.core;
  requires com.fasterxml.jackson.annotation;
  requires com.fasterxml.jackson.databind;
  requires java.net.http;
  requires static transitive org.jspecify;

  exports page.poli.sdk;
  exports page.poli.sdk.input;
  exports page.poli.sdk.model;
  exports page.poli.sdk.exception;

  // Top-level package is opened to Jackson for the SDK's private wire-envelope records
  // (e.g. Documents.ThumbnailResponse, DocumentsAsync.ThumbnailResponse) — Jackson reflects on
  // the canonical record constructor to deserialize. The package's classes that aren't records
  // are unaffected.
  opens page.poli.sdk to
      com.fasterxml.jackson.databind;
  opens page.poli.sdk.input to
      com.fasterxml.jackson.databind;
  opens page.poli.sdk.model to
      com.fasterxml.jackson.databind;
}
