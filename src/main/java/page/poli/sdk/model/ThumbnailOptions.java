package page.poli.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Options for {@code client.documents().thumbnails(id, options)}. Construct via {@link #builder()}.
 *
 * <p>Wire-shape envelope: the SDK wraps this object under a {@code "thumbnails"} key when sending —
 * consumers don't see that detail.
 *
 * @param width thumbnail width in pixels; required, must be positive
 * @param format output format; required (no implicit default in the wire envelope — the deployed
 *     API requires it explicitly, even though Node defaults to PNG client-side)
 * @param quality JPEG quality 1–100; ignored / forbidden when {@link #format} is {@link
 *     ThumbnailFormat#PNG}
 * @param pages 1-based page numbers to render; {@code null} renders all pages
 * @param idempotencyKey caller-supplied idempotency key; when set, overrides the SDK-generated UUID
 *     sent in the {@code Idempotency-Key} request header — never serialised to the wire body
 */
@JsonInclude(Include.NON_NULL)
public record ThumbnailOptions(
    @JsonProperty("width") int width,
    @JsonProperty("format") ThumbnailFormat format,
    @JsonProperty("quality") @Nullable Integer quality,
    @JsonProperty("pages") @Nullable List<Integer> pages,
    @JsonIgnore @Nullable String idempotencyKey) {

  /** Compact-constructor invariants. */
  public ThumbnailOptions {
    Objects.requireNonNull(format, "format");
    if (width <= 0) {
      throw new IllegalArgumentException("width must be > 0, got: " + width);
    }
    if (quality != null) {
      if (format != ThumbnailFormat.JPEG) {
        throw new IllegalArgumentException(
            "quality is only valid when format is JPEG, got format=" + format);
      }
      if (quality < 1 || quality > 100) {
        throw new IllegalArgumentException("quality must be in [1, 100], got: " + quality);
      }
    }
    if (pages != null) {
      pages = List.copyOf(pages); // defensive copy
      for (int p : pages) {
        if (p < 1) {
          throw new IllegalArgumentException("pages must be 1-based positive integers, got: " + p);
        }
      }
    }
  }

  /**
   * Returns a fresh {@link Builder}.
   *
   * @return a new builder with no fields set; format defaults to {@link ThumbnailFormat#PNG} at
   *     {@link Builder#build()} time
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for {@link ThumbnailOptions}. */
  public static final class Builder {

    private @Nullable Integer width;
    private ThumbnailFormat format = ThumbnailFormat.PNG;
    private @Nullable Integer quality;
    private @Nullable List<Integer> pages;
    private @Nullable String idempotencyKey;

    private Builder() {}

    /**
     * Sets the thumbnail width in pixels. Required.
     *
     * @param width positive pixel width
     * @return this builder
     */
    public Builder width(int width) {
      this.width = width;
      return this;
    }

    /**
     * Sets the output format. Defaults to {@link ThumbnailFormat#PNG}.
     *
     * @param format non-null format
     * @return this builder
     */
    public Builder format(ThumbnailFormat format) {
      this.format = Objects.requireNonNull(format, "format");
      return this;
    }

    /**
     * Sets the JPEG quality (1–100). Only valid when format is {@link ThumbnailFormat#JPEG}.
     *
     * @param quality 1–100, or {@code null} to clear
     * @return this builder
     */
    public Builder quality(@Nullable Integer quality) {
      this.quality = quality;
      return this;
    }

    /**
     * Restricts rendering to the listed 1-based page numbers.
     *
     * @param pages list of positive integers, or {@code null} to render all pages
     * @return this builder
     */
    public Builder pages(@Nullable List<Integer> pages) {
      this.pages = pages == null ? null : List.copyOf(pages);
      return this;
    }

    /**
     * Sets a caller-supplied idempotency key. Optional — when omitted, the SDK generates a UUID.
     * The key is sent in the {@code Idempotency-Key} request header and is never serialised to the
     * wire body.
     *
     * @param idempotencyKey the idempotency key, or {@code null} to let the SDK generate one
     * @return this builder
     */
    public Builder idempotencyKey(@Nullable String idempotencyKey) {
      this.idempotencyKey = idempotencyKey;
      return this;
    }

    /**
     * Validates and produces an immutable {@link ThumbnailOptions}.
     *
     * @return the options record
     * @throws NullPointerException if {@code width} was never set
     * @throws IllegalArgumentException on validation failures (see record compact constructor)
     */
    public ThumbnailOptions build() {
      return new ThumbnailOptions(
          Objects.requireNonNull(width, "width is required"),
          format,
          quality,
          pages,
          idempotencyKey);
    }
  }
}
