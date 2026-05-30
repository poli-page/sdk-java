package page.poli.sdk;

import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Immutable snapshot of every option that shapes a {@link PoliPageClient}'s behaviour. Built by
 * {@link PoliPageClient.Builder#build()} and stored on the client for the lifetime of the instance.
 *
 * <p>Package-private on purpose — consumers reach the option values through the client's facade
 * methods, never directly. Tests in the same package access this for default-validation assertions.
 */
record PoliPageClientOptions(
    String apiKey,
    URI baseUrl,
    int maxRetries,
    Duration retryDelay,
    Duration requestTimeout,
    @Nullable Consumer<RetryEvent> onRetry,
    @Nullable Consumer<Throwable> onError) {}
