package page.poli.sdk;

/**
 * Event passed to a registered {@code onRequest} hook immediately before the SDK starts an HTTP
 * exchange. Fires once per attempt — so attempt {@code 2} on retry produces a second event.
 *
 * <p>Mirrors sdk-node's {@code RequestEvent} ({@code types.ts:171-175}).
 *
 * @param method HTTP method ({@code "GET"}, {@code "POST"}, {@code "DELETE"})
 * @param url fully-qualified request URL ({@code baseUrl + path})
 * @param attempt 1-based attempt number (initial attempt is {@code 1}; first retry is {@code 2})
 */
public record RequestEvent(String method, String url, int attempt) {}
