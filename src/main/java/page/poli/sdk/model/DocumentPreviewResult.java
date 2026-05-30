package page.poli.sdk.model;

/**
 * Result of {@code client.documents().preview(id)}. The deployed API responds with {@code
 * text/html} directly (not a JSON envelope) and exposes the page count via the {@code
 * X-Document-Page-Count} response header — the SDK assembles this envelope from those two pieces.
 *
 * <p>Note: the field name is {@code pageCount} here, but {@code render().preview} uses {@code
 * totalPages}. That mismatch is in the deployed API, not in the SDK — they're different endpoints
 * with independent shapes.
 *
 * @param html stored HTML body
 * @param pageCount number of pages in the stored document — {@code 0} if the {@code
 *     X-Document-Page-Count} header was missing or malformed
 */
public record DocumentPreviewResult(String html, int pageCount) {}
