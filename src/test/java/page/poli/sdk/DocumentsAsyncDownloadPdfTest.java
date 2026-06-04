package page.poli.sdk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.model.DocumentDescriptor;

@WireMockTest
class DocumentsAsyncDownloadPdfTest {

  private static final byte[] PDF = "%PDF-1.4\nbody".getBytes(StandardCharsets.UTF_8);
  private static final String PRESIGNED_PATH = "/presigned/doc.pdf";

  private static PoliPageClient client(WireMockRuntimeInfo wm) {
    return PoliPageClient.builder()
        .apiKey("pp_test_x")
        .baseUrl(URI.create(wm.getHttpBaseUrl()))
        .maxRetries(0)
        .build();
  }

  private static DocumentDescriptor descriptorWithUrl(String url) {
    return new DocumentDescriptor(
        "doc_x", "org", null, null, null, null, null, "sandbox",
        null, "A4", null, null, 1, 1L, "2026-01-01T00:00:00Z", Map.of(),
        url, "2026-01-01T00:15:00Z");
  }

  @Test
  void downloadPdf_returns_bytes_on_2xx(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlPathEqualTo(PRESIGNED_PATH))
            .willReturn(aResponse().withStatus(200).withBody(PDF)));

    byte[] result =
        client(wm)
            .documentsAsync()
            .downloadPdf(descriptorWithUrl(wm.getHttpBaseUrl() + PRESIGNED_PATH))
            .join();

    assertThat(result).isEqualTo(PDF);
  }

  @Test
  void downloadPdf_completes_exceptionally_on_non_2xx(WireMockRuntimeInfo wm) {
    stubFor(get(urlPathEqualTo(PRESIGNED_PATH)).willReturn(aResponse().withStatus(404)));

    assertThatThrownBy(
            () ->
                client(wm)
                    .documentsAsync()
                    .downloadPdf(descriptorWithUrl(wm.getHttpBaseUrl() + PRESIGNED_PATH))
                    .join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PoliPageDownloadException.class);
  }
}
