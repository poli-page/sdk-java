// Demonstrates: client.documents().preview(id) — fetch the stored HTML preview.
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.model.DocumentPreviewResult;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        DocumentPreviewResult preview = client.documents().preview("doc_abc123");

        System.out.println("Preview: " + preview.pageCount() + " page(s)");
        System.out.println(preview.html());
    }
}
