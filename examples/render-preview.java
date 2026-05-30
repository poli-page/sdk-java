// Demonstrates: client.render().preview(input) — render HTML preview (no stored document).
import java.util.Map;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.input.InlineModeInput;
import page.poli.sdk.model.PreviewResult;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        // preview accepts both ProjectModeInput and InlineModeInput via the
        // sealed RenderInput supertype. Inline mode is shown here.
        InlineModeInput input = InlineModeInput.builder()
            .template("<h1>Hello {{ name }}</h1>")
            .data(Map.of("name", "World"))
            .build();

        PreviewResult preview = client.render().preview(input);

        System.out.println("Preview: " + preview.totalPages() + " page(s) in "
            + preview.environment());
        System.out.println(preview.html());
    }
}
