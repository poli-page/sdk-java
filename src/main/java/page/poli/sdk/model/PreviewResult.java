package page.poli.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of {@code client.render().preview(...)}. Carries the rendered HTML, the total page count
 * for the rendered document, and the environment the key resolved to.
 *
 * <p>Note: {@code render().preview} uses {@code totalPages}, while {@code
 * client.documents().preview(...)} (Phase 6) uses {@code pageCount} — that mismatch is on the
 * deployed API, not the SDK.
 *
 * @param html rendered HTML body
 * @param totalPages number of pages in the rendered document
 * @param environment the resolved key environment, typically {@code "sandbox"} or {@code "live"}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PreviewResult(
    @JsonProperty("html") String html,
    @JsonProperty("totalPages") int totalPages,
    @JsonProperty("environment") String environment) {}
