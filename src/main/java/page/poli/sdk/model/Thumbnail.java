package page.poli.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single page thumbnail returned by {@code client.documents().thumbnails(...)}.
 *
 * @param page 1-based page number this thumbnail represents
 * @param width actual pixel width of the thumbnail
 * @param height actual pixel height of the thumbnail
 * @param contentType MIME type of the encoded bytes — typically {@code "image/png"} or
 *     {@code "image/jpeg"}
 * @param data Base64-encoded image bytes; decode with {@link java.util.Base64#getDecoder()}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Thumbnail(
    @JsonProperty("page") int page,
    @JsonProperty("width") int width,
    @JsonProperty("height") int height,
    @JsonProperty("contentType") String contentType,
    @JsonProperty("data") String data) {}
