package page.poli.sdk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import page.poli.sdk.exception.PoliPageGoneException;
import page.poli.sdk.exception.PoliPageNotFoundException;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.DocumentPreviewResult;
import page.poli.sdk.model.Thumbnail;
import page.poli.sdk.model.ThumbnailOptions;

@WireMockTest
class DocumentsAsyncTest {

  private static final String TEST_KEY = "pp_test_secret";
  private static final String DOC_ID = "doc_async";
  private static final String DOC_PATH = "/v1/documents/" + DOC_ID;

  private static final String DOC_JSON =
      "{"
          + "\"documentId\":\"" + DOC_ID + "\","
          + "\"organizationId\":\"org_42\","
          + "\"environment\":\"sandbox\","
          + "\"format\":\"A4\","
          + "\"pageCount\":1,"
          + "\"sizeBytes\":100,"
          + "\"createdAt\":\"2026-05-29T10:00:00Z\","
          + "\"metadata\":{},"
          + "\"presignedPdfUrl\":\"https://s3.example/x.pdf\","
          + "\"expiresAt\":\"2026-05-29T10:15:00Z\""
          + "}";

  private static PoliPageClient newClient(WireMockRuntimeInfo wm) {
    return PoliPageClient.builder()
        .apiKey(TEST_KEY)
        .baseUrl(URI.create(wm.getHttpBaseUrl()))
        .maxRetries(0)
        .build();
  }

  @Test
  void get_returns_descriptor_via_future(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo(DOC_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(DOC_JSON)));

    DocumentDescriptor d = newClient(wm).documentsAsync().get(DOC_ID).join();

    assertThat(d.documentId()).isEqualTo(DOC_ID);
  }

  @Test
  void preview_assembles_html_and_pageCount_via_future(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo(DOC_PATH + "/preview"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/html")
                    .withHeader("X-Document-Page-Count", "7")
                    .withBody("<html>p</html>")));

    DocumentPreviewResult r = newClient(wm).documentsAsync().preview(DOC_ID).join();

    assertThat(r.html()).isEqualTo("<html>p</html>");
    assertThat(r.pageCount()).isEqualTo(7);
  }

  @Test
  void thumbnails_wraps_and_unwraps_via_future(WireMockRuntimeInfo wm) {
    stubFor(
        post(urlEqualTo(DOC_PATH + "/thumbnails"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"thumbnails\":["
                            + "{\"page\":1,\"width\":320,\"height\":451,"
                            + "\"contentType\":\"image/png\",\"data\":\"aGVsbG8=\"}"
                            + "]}")));

    List<Thumbnail> result =
        newClient(wm)
            .documentsAsync()
            .thumbnails(DOC_ID, ThumbnailOptions.builder().width(320).build())
            .join();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).data()).isEqualTo("aGVsbG8=");

    verify(
        postRequestedFor(urlEqualTo(DOC_PATH + "/thumbnails"))
            .withRequestBody(
                equalToJson("{\"thumbnails\":{\"width\":320,\"format\":\"png\"}}")));
  }

  @Test
  void delete_completes_with_null_value_on_success(WireMockRuntimeInfo wm) {
    stubFor(delete(urlEqualTo(DOC_PATH)).willReturn(aResponse().withStatus(204)));

    Void result = newClient(wm).documentsAsync().delete(DOC_ID).join();

    // CompletableFuture<Void> always completes with null on success.
    assertThat(result).isNull();
  }

  @Test
  void error_wraps_in_CompletionException_with_typed_cause(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo(DOC_PATH))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"DOCUMENT_NOT_FOUND\",\"message\":\"x\"}")));

    assertThatThrownBy(() -> newClient(wm).documentsAsync().get(DOC_ID).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PoliPageNotFoundException.class);
  }

  @Test
  void delete_410_wraps_in_CompletionException_with_PoliPageGoneException(
      WireMockRuntimeInfo wm) {
    stubFor(
        delete(urlEqualTo(DOC_PATH))
            .willReturn(
                aResponse()
                    .withStatus(410)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"code\":\"GONE\",\"message\":\"already deleted\"}")));

    assertThatThrownBy(() -> newClient(wm).documentsAsync().delete(DOC_ID).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PoliPageGoneException.class);
  }
}
