// Demonstrates: client.documents().thumbnails(id, options) — request page thumbnails.
import java.util.Base64;
import java.util.List;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.model.Thumbnail;
import page.poli.sdk.model.ThumbnailFormat;
import page.poli.sdk.model.ThumbnailOptions;

class Example {
    public static void main(String[] args) {
        PoliPageClient client = PoliPageClient.builder()
            .apiKey(System.getenv("POLI_PAGE_API_KEY"))
            .build();

        ThumbnailOptions options = ThumbnailOptions.builder()
            .width(480)
            .format(ThumbnailFormat.PNG)
            .pages(List.of(1))
            .build();

        List<Thumbnail> thumbnails = client.documents().thumbnails("doc_abc123", options);

        for (Thumbnail t : thumbnails) {
            byte[] bytes = Base64.getDecoder().decode(t.data());
            System.out.println("Page " + t.page() + ": " + t.contentType()
                + " " + t.width() + "x" + t.height() + " (" + bytes.length + " bytes)");
        }
    }
}
