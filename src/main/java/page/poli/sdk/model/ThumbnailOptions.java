package page.poli.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Options for {@code client.documents().thumbnails(id, options)}. Construct via {@link #builder()}.
 *
 * <p>Every option is optional: an unset option is not sent and the API applies its default. The SDK
 * does not check bounds; out-of-range values get {@code 400 VALIDATION_ERROR} from the API.
 *
 * <p>Wire-shape envelope: the SDK wraps this object under a {@code "thumbnails"} key when sending —
 * consumers don't see that detail. With no option set, the request body is {@code {}}.
 *
 * @param width thumbnail width in pixels, 50–2000; {@code null} uses the API default (400)
 * @param format output format; {@code null} uses the API default ({@link ThumbnailFormat#JPEG})
 * @param quality image quality 1–100, {@code jpeg} only; {@code null} uses the API default
 * @param pages 1-based page numbers to render; {@code null} renders all pages
 * @param idempotencyKey caller-supplied idempotency key; when set, overrides the SDK-generated UUID
 *     sent in the {@code Idempotency-Key} request header — never serialised to the wire body
 */
@JsonInclude(Include.NON_NULL)
public record ThumbnailOptions(
    @JsonProperty("width") @Nullable Integer width,
    @JsonProperty("format") @Nullable ThumbnailFormat format,
    @JsonProperty("quality") @Nullable Integer quality,
    @JsonProperty("pages") @Nullable List<Integer> pages,
    @JsonIgnore @Nullable String idempotencyKey) {

  /** Compact constructor: defensive copy of {@code pages}. */
  public ThumbnailOptions {
    if (pages != null) {
      pages = List.copyOf(pages);
    }
  }

  /**
   * Returns a fresh {@link Builder}.
   *
   * @return a new builder with no option set
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for {@link ThumbnailOptions}. */
  public static final class Builder {

    private @Nullable Integer width;
    private @Nullable ThumbnailFormat format;
    private @Nullable Integer quality;
    private @Nullable List<Integer> pages;
    private @Nullable String idempotencyKey;

    private Builder() {}

    /**
     * Sets the thumbnail width in pixels (API range 50–2000, default 400). Optional.
     *
     * @param width pixel width, or {@code null} to use the API default
     * @return this builder
     */
    public Builder width(@Nullable Integer width) {
      this.width = width;
      return this;
    }

    /**
     * Sets the output format (API default {@link ThumbnailFormat#JPEG}). Optional.
     *
     * @param format the format, or {@code null} to use the API default
     * @return this builder
     */
    public Builder format(@Nullable ThumbnailFormat format) {
      this.format = format;
      return this;
    }

    /**
     * Sets the image quality (1–100, {@code jpeg} only). Optional.
     *
     * @param quality 1–100, or {@code null} to use the API default
     * @return this builder
     */
    public Builder quality(@Nullable Integer quality) {
      this.quality = quality;
      return this;
    }

    /**
     * Restricts rendering to the listed 1-based page numbers. Optional.
     *
     * @param pages page numbers, or {@code null} to render all pages
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
     * Produces an immutable {@link ThumbnailOptions}.
     *
     * @return the options record
     */
    public ThumbnailOptions build() {
      return new ThumbnailOptions(width, format, quality, pages, idempotencyKey);
    }
  }
}
