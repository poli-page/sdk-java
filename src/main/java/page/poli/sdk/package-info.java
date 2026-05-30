/**
 * Poli Page SDK for Java — render polished PDFs from HTML templates via the Poli Page API.
 *
 * <p>The entry point is {@code PoliPageClient}, constructed via {@code PoliPageClient.builder()}.
 * Two facades sit on the same client: a blocking surface ({@code client.render()}, {@code
 * client.documents()}) and a {@link java.util.concurrent.CompletableFuture}-based async surface
 * ({@code client.renderAsync()}, {@code client.documentsAsync()}). Both share one HTTP connection
 * pool, one retry policy, and one exception hierarchy.
 *
 * <p>See <a href="https://github.com/poli-page/sdk-java">the project README</a> for installation,
 * the quick-start, and the migration guide.
 */
@NullMarked
package page.poli.sdk;

import org.jspecify.annotations.NullMarked;
