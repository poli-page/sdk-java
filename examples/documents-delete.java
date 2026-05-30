// Demonstrates: client.documents().delete(id) — soft-delete a stored document.
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.exception.PoliPageGoneException;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        client.documents().delete("doc_abc123");
        System.out.println("Deleted doc_abc123");

        // Subsequent fetches throw PoliPageGoneException (HTTP 410).
        try {
            client.documents().get("doc_abc123");
        } catch (PoliPageGoneException ex) {
            System.out.println("Confirmed gone: " + ex.code());
        }
    }
}
