// Demonstrates: client.render().pdfStream(input) — stream PDF bytes without buffering.
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.input.ProjectModeInput;

class Example {
    public static void main(String[] args) throws Exception {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        ProjectModeInput input = ProjectModeInput.builder()
            .project("billing")
            .template("invoice")
            .data(Map.of("invoiceNumber", "INV-001", "total", 1280))
            .build();

        // The returned InputStream owns the HTTP connection — try-with-resources
        // is the only safe way to use it.
        try (InputStream pdf = client.render().pdfStream(input)) {
            long bytes = Files.copy(pdf, Path.of("./invoices/INV-001.pdf"));
            System.out.println("Streamed " + bytes + " bytes");
        }
    }
}
