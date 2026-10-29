package page.poli.sdk;

import org.jspecify.annotations.Nullable;

/**
 * Event passed to a registered {@code onResponse} hook after the SDK reads a 2xx API answer, body
 * included. Error answers do not fire it.
 *
 * <p>Mirrors sdk-node's {@code ResponseEvent} ({@code types.ts:177-181}).
 *
 * @param status HTTP status code as observed
 * @param requestId value of the {@code x-request-id} response header, or {@code null} when absent
 * @param durationMs duration of the attempt, from its start to the end of the body, in milliseconds
 */
public record ResponseEvent(int status, @Nullable String requestId, long durationMs) {}
