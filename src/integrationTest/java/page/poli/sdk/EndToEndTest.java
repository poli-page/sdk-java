package page.poli.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import page.poli.sdk.input.ProjectModeInput;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.PreviewResult;

/**
 * End-to-end smoke against the deployed Poli Page API. Enabled only when {@code POLI_PAGE_API_KEY}
 * is present in the environment, so unit-only contributors aren't forced to set credentials.
 *
 * <p>Override the target environment with {@code POLI_PAGE_BASE_URL} (defaults to the production
 * URL, which the {@code pp_test_*} sandbox key resolves against transparently). Runs via the
 * {@code integration-tests} Maven profile: {@code ./mvnw -P integration-tests verify}.
 */
@EnabledIfEnvironmentVariable(named = "POLI_PAGE_API_KEY", matches = "pp_.+")
class EndToEndTest {

  private static PoliPageClient newClient() {
    String apiKey = System.getenv("POLI_PAGE_API_KEY");
    String baseUrl = System.getenv("POLI_PAGE_BASE_URL");
    var builder = PoliPageClient.builder().apiKey(apiKey);
    if (baseUrl != null && !baseUrl.isBlank()) {
      builder.baseUrl(URI.create(baseUrl));
    }
    return builder.build();
  }

  private static ProjectModeInput welcomeInput() {
    return ProjectModeInput.builder()
        .project("getting-started")
        .template("welcome")
        .data(Map.of("name", "sdk-java integration test"))
        .build();
  }

  @Test
  void render_pdf_returns_non_empty_pdf_bytes() {
    byte[] pdf = newClient().render().pdf(welcomeInput());

    assertThat(pdf).hasSizeGreaterThan(1024);
    assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
  }

  @Test
  void render_document_returns_descriptor_with_presigned_url() {
    DocumentDescriptor doc = newClient().render().document(welcomeInput());

    assertThat(doc.documentId()).isNotBlank();
    assertThat(doc.presignedPdfUrl()).startsWith("https://");
    assertThat(doc.pageCount()).isPositive();
  }

  @Test
  void render_preview_returns_html() {
    PreviewResult preview = newClient().render().preview(welcomeInput());

    assertThat(preview.html()).contains("<");
    assertThat(preview.totalPages()).isPositive();
  }
}
