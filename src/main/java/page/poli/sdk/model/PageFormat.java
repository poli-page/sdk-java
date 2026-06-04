package page.poli.sdk.model;

/**
 * Canonical paper-size enum shared across every Poli Page SDK. Wire strings are the enum constants
 * verbatim (Pascal-case for the named formats, ISO codes for the metric formats) — Jackson
 * serializes by {@link Enum#name()} by default, which matches the wire spec.
 *
 * <p>See {@code docs/spec/sdk-specification.md} §5.3 (canonical PageFormat list) for the full table
 * mapping each value to its physical dimensions.
 */
public enum PageFormat {
  A3,
  A4,
  A5,
  A6,
  B4,
  B5,
  Letter,
  Legal,
  Tabloid,
  Executive,
  Statement,
  Folio
}
