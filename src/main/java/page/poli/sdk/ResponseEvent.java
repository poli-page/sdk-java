package page.poli.sdk;

import org.jspecify.annotations.Nullable;

/**
 * Event passed to a registered {@code onResponse} hook immediately after the SDK reads an HTTP
 * response (success or failure status). Fires once per attempt.
 *
 * <p>Mirrors sdk-node's {@code ResponseEvent} ({@code types.ts:177-181}).
 *
 * @param status HTTP status code as observed
 * @param requestId value of the {@code x-request-id} response header, or {@code null} when absent
 * @param durationMs wall-clock duration from request start to response observed, in milliseconds
 */
public record ResponseEvent(int status, @Nullable String requestId, long durationMs) {}
