package page.poli.sdk;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Event passed to a registered {@code onRetry} hook just before the SDK sleeps between attempts.
 *
 * <p>Use it to feed metrics, traces, or structured logs without instrumenting the SDK's own
 * logging:
 *
 * <pre>{@code
 * PoliPageClient.builder()
 *     .apiKey(System.getenv("POLI_PAGE_API_KEY"))
 *     .onRetry(event -> metrics.counter("poli.retry")
 *         .tag("attempt", String.valueOf(event.attempt()))
 *         .tag("reason", event.reason())
 *         .increment())
 *     .build();
 * }</pre>
 *
 * @param attempt number of the attempt about to start, 1-based like {@link RequestEvent#attempt()}:
 *     {@code 2} for the first retry
 * @param delay how long the SDK will wait before that attempt (post-jitter, or the {@code
 *     Retry-After} value clamped to 30 s)
 * @param statusCode HTTP status code that triggered the retry, or {@code null} for transport
 *     failures with no HTTP response (network / timeout)
 * @param reason short stable string identifying why the retry happened — one of {@code "5xx"},
 *     {@code "rate_limit"}, {@code "timeout"}, {@code "network_error"}
 */
public record RetryEvent(
    int attempt, Duration delay, @Nullable Integer statusCode, String reason) {}
