// Demonstrates: PoliPageClient.builder() — constructing a configured client.
import java.net.URI;
import java.time.Duration;
import page.poli.sdk.PoliPageClient;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .baseUrl(URI.create("https://api.poli.page"))
            .requestTimeout(Duration.ofSeconds(60))
            .maxRetries(2)
            .retryDelay(Duration.ofMillis(500))
            .onRetry(event -> System.out.println(
                "retry attempt=" + event.attempt() + " reason=" + event.reason()))
            .onError(err -> System.err.println("terminal: " + err.getMessage()))
            .build();

        // The same client instance is reused across requests; it owns the
        // HTTP connection pool.
        System.out.println("Client ready against " + client);
    }
}
