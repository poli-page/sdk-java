package page.poli.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Wire-shape representation of a stored document. Returned by {@code POST /v1/render} (via
 * {@link page.poli.sdk.Render#document}) and {@code GET /v1/documents/{id}} (via {@code
 * client.documents().get(...)} in Phase 6).
 *
 * <p>Top-level fields are system-controlled; {@link #metadata} echoes the caller-supplied bag.
 * Use {@link #presignedPdfUrl()} to fetch the rendered PDF bytes (the URL has a short TTL — get a
 * fresh one by calling {@code documents().get(id)} when {@link #expiresAt} is past).
 *
 * <p>The matching shape on the Node SDK is {@code RawDocumentDescriptor}; the deployed API is the
 * source of truth when the two disagree.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentDescriptor(
    @JsonProperty("documentId") String documentId,
    @JsonProperty("organizationId") String organizationId,
    @JsonProperty("projectId") @Nullable String projectId,
    @JsonProperty("projectSlug") @Nullable String projectSlug,
    @JsonProperty("templateId") @Nullable String templateId,
    @JsonProperty("templateSlug") @Nullable String templateSlug,
    @JsonProperty("version") @Nullable String version,
    @JsonProperty("environment") String environment,
    @JsonProperty("apiKeyId") @Nullable String apiKeyId,
    @JsonProperty("format") String format,
    @JsonProperty("orientation") @Nullable String orientation,
    @JsonProperty("locale") @Nullable String locale,
    @JsonProperty("pageCount") int pageCount,
    @JsonProperty("sizeBytes") long sizeBytes,
    @JsonProperty("createdAt") String createdAt,
    @JsonProperty("metadata") Map<String, Object> metadata,
    @JsonProperty("presignedPdfUrl") String presignedPdfUrl,
    @JsonProperty("expiresAt") String expiresAt) {}
