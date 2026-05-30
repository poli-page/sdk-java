// Demonstrates: client.render().pdf(input) — project mode only.
import java.util.Map;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.input.ProjectModeInput;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        ProjectModeInput input = ProjectModeInput.builder()
            .project("billing")
            .template("invoice")
            .data(Map.of("invoiceNumber", "INV-001", "total", 1280))
            .build();

        byte[] pdf = client.render().pdf(input);

        // `pdf` is a byte[] of PDF bytes.
        System.out.println("Rendered " + pdf.length + " bytes");
    }
}
