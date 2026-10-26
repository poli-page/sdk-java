// Demonstrates: client.renderToFile(input, path) — convenience helper.
import java.nio.file.Path;
import java.util.Map;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.input.RenderInput;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        RenderInput input = RenderInput.builder()
            .project("billing")
            .template("invoice")
            .data(Map.of("invoiceNumber", "INV-001", "total", 1280))
            .build();

        client.renderToFile(input, Path.of("./invoices/INV-001.pdf"));
        System.out.println("Wrote ./invoices/INV-001.pdf");
    }
}
