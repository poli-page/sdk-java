// Demonstrates: client.render().pdf(input).
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

        byte[] pdf = client.render().pdf(input);

        // `pdf` is a byte[] of PDF bytes.
        System.out.println("Rendered " + pdf.length + " bytes");
    }
}
