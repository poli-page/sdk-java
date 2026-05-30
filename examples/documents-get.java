// Demonstrates: client.documents().get(id) — fetch a stored document descriptor.
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.model.DocumentDescriptor;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        DocumentDescriptor document = client.documents().get("doc_abc123");

        System.out.println("Document " + document.documentId() + ": "
            + document.pageCount() + " pages, created " + document.createdAt());

        // `presignedPdfUrl()` has a 15-minute TTL. Fetch the bytes before it
        // expires, or call documents().get(id) again to refresh.
        System.out.println("Presigned URL: " + document.presignedPdfUrl());
    }
}
