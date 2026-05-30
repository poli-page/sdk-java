// Demonstrates: client.render().document(input) — render and store a PDF server-side.
import java.util.Map;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.input.ProjectModeInput;
import page.poli.sdk.model.DocumentDescriptor;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        ProjectModeInput input = ProjectModeInput.builder()
            .project("billing")
            .template("invoice")
            .data(Map.of("invoiceNumber", "INV-001", "total", 1280))
            .metadata(Map.of("customerId", "cust_42"))
            .build();

        DocumentDescriptor document = client.render().document(input);

        // `document.documentId()` identifies the stored document — use it with
        // client.documents().* to fetch, preview, thumbnail, or delete later.
        System.out.println("Stored as " + document.documentId()
            + " (" + document.pageCount() + " pages, "
            + document.sizeBytes() + " bytes)");
        System.out.println("Presigned URL: " + document.presignedPdfUrl());
    }
}
