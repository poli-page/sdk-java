package page.poli.sdk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import page.poli.sdk.exception.PoliPageGoneException;
import page.poli.sdk.exception.PoliPageNotFoundException;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.DocumentPreviewResult;
import page.poli.sdk.model.Thumbnail;
import page.poli.sdk.model.ThumbnailFormat;
import page.poli.sdk.model.ThumbnailOptions;

@WireMockTest
class DocumentsTest {

  private static final String TEST_KEY = "pp_test_secret";
  private static final String DOC_ID = "doc_abc123";
  private static final String DOC_PATH = "/v1/documents/" + DOC_ID;

  private static final String DOC_JSON =
      "{"
          + "\"documentId\":\""
          + DOC_ID
          + "\","
          + "\"organizationId\":\"org_42\","
          + "\"environment\":\"sandbox\","
          + "\"format\":\"A4\","
          + "\"pageCount\":3,"
          + "\"sizeBytes\":42000,"
          + "\"createdAt\":\"2026-05-29T10:00:00Z\","
          + "\"metadata\":{},"
          + "\"presignedPdfUrl\":\"https://s3.example/doc_abc123.pdf\","
          + "\"expiresAt\":\"2026-05-29T10:15:00Z\""
          + "}";

  private static PoliPageClient newClient(WireMockRuntimeInfo wm) {
    return PoliPageClient.builder()
        .apiKey(TEST_KEY)
        .baseUrl(URI.create(wm.getHttpBaseUrl()))
        .maxRetries(0)
        .build();
  }

  // =================================================================
  // get(id)
  // =================================================================

  @Nested
  class Get {

    @Test
    void get_returns_parsed_descriptor(WireMockRuntimeInfo wm) {
      stubFor(
          get(urlEqualTo(DOC_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(DOC_JSON)));

      DocumentDescriptor d = newClient(wm).documents().get(DOC_ID);

      assertThat(d.documentId()).isEqualTo(DOC_ID);
      assertThat(d.pageCount()).isEqualTo(3);
      assertThat(d.sizeBytes()).isEqualTo(42000L);
    }

    @Test
    void get_sends_bearer_token_and_no_content_type(WireMockRuntimeInfo wm) {
      stubFor(
          get(urlEqualTo(DOC_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(DOC_JSON)));

      newClient(wm).documents().get(DOC_ID);

      verify(
          getRequestedFor(urlEqualTo(DOC_PATH))
              .withHeader(
                  "Authorization",
                  new com.github.tomakehurst.wiremock.matching.EqualToPattern("Bearer " + TEST_KEY))
              .withHeader(
                  "Accept",
                  new com.github.tomakehurst.wiremock.matching.EqualToPattern("application/json"))
              .withoutHeader("Content-Type")
              .withoutHeader("Idempotency-Key"));
    }

    @Test
    void get_404_throws_PoliPageNotFoundException(WireMockRuntimeInfo wm) {
      stubFor(
          get(urlEqualTo(DOC_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(404)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"code\":\"DOCUMENT_NOT_FOUND\",\"message\":\"x\"}")));

      assertThatThrownBy(() -> newClient(wm).documents().get(DOC_ID))
          .isInstanceOf(PoliPageNotFoundException.class);
    }

    @Test
    void get_url_encodes_the_id(WireMockRuntimeInfo wm) {
      // "doc/with spaces+plus" → "doc%2Fwith%20spaces%2Bplus"
      String weirdId = "doc/with spaces+plus";
      String encodedPath = "/v1/documents/doc%2Fwith%20spaces%2Bplus";
      stubFor(
          get(urlEqualTo(encodedPath))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(DOC_JSON)));

      newClient(wm).documents().get(weirdId);

      verify(getRequestedFor(urlEqualTo(encodedPath)));
    }
  }

  // =================================================================
  // preview(id) — text/html + X-Document-Page-Count
  // =================================================================

  @Nested
  class Preview {

    private static final String PREVIEW_PATH = DOC_PATH + "/preview";

    @Test
    void preview_returns_html_and_page_count(WireMockRuntimeInfo wm) {
      stubFor(
          get(urlEqualTo(PREVIEW_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "text/html; charset=utf-8")
                      .withHeader("X-Document-Page-Count", "5")
                      .withBody("<html>preview body</html>")));

      DocumentPreviewResult result = newClient(wm).documents().preview(DOC_ID);

      assertThat(result.html()).isEqualTo("<html>preview body</html>");
      assertThat(result.pageCount()).isEqualTo(5);
    }

    @Test
    void preview_defaults_pageCount_to_zero_when_header_missing(WireMockRuntimeInfo wm) {
      stubFor(
          get(urlEqualTo(PREVIEW_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "text/html; charset=utf-8")
                      .withBody("<html></html>")));

      DocumentPreviewResult result = newClient(wm).documents().preview(DOC_ID);

      assertThat(result.pageCount()).isZero();
    }

    @Test
    void preview_defaults_pageCount_to_zero_when_header_malformed(WireMockRuntimeInfo wm) {
      stubFor(
          get(urlEqualTo(PREVIEW_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "text/html")
                      .withHeader("X-Document-Page-Count", "not-a-number")
                      .withBody("<html></html>")));

      DocumentPreviewResult result = newClient(wm).documents().preview(DOC_ID);

      assertThat(result.pageCount()).isZero();
    }

    @Test
    void preview_404_throws_PoliPageNotFoundException(WireMockRuntimeInfo wm) {
      stubFor(
          get(urlEqualTo(PREVIEW_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(404)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"code\":\"DOCUMENT_NOT_FOUND\",\"message\":\"x\"}")));

      assertThatThrownBy(() -> newClient(wm).documents().preview(DOC_ID))
          .isInstanceOf(PoliPageNotFoundException.class);
    }
  }

  // =================================================================
  // thumbnails(id, options) — body wrap + response unwrap
  // =================================================================

  @Nested
  class Thumbnails {

    private static final String THUMB_PATH = DOC_PATH + "/thumbnails";

    private static final String THUMB_RESPONSE_JSON =
        "{\"thumbnails\":["
            + "{\"page\":1,\"width\":320,\"height\":451,\"contentType\":\"image/png\",\"data\":\"aGVsbG8=\"},"
            + "{\"page\":2,\"width\":320,\"height\":451,\"contentType\":\"image/png\",\"data\":\"d29ybGQ=\"}"
            + "]}";

    private void stubThumbnailsOk() {
      stubFor(
          post(urlEqualTo(THUMB_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(THUMB_RESPONSE_JSON)));
    }

    @Test
    void thumbnails_returns_list_unwrapped_from_envelope(WireMockRuntimeInfo wm) {
      stubThumbnailsOk();

      List<Thumbnail> result =
          newClient(wm)
              .documents()
              .thumbnails(DOC_ID, ThumbnailOptions.builder().width(320).build());

      assertThat(result).hasSize(2);
      assertThat(result.get(0).page()).isEqualTo(1);
      assertThat(result.get(0).data()).isEqualTo("aGVsbG8=");
      assertThat(result.get(1).page()).isEqualTo(2);
    }

    @Test
    void thumbnails_wraps_body_under_thumbnails_key(WireMockRuntimeInfo wm) {
      stubThumbnailsOk();

      newClient(wm)
          .documents()
          .thumbnails(
              DOC_ID,
              ThumbnailOptions.builder()
                  .width(320)
                  .format(ThumbnailFormat.JPEG)
                  .quality(80)
                  .pages(List.of(1, 2))
                  .build());

      verify(
          postRequestedFor(urlEqualTo(THUMB_PATH))
              .withRequestBody(
                  equalToJson(
                      "{\"thumbnails\":{"
                          + "\"width\":320,"
                          + "\"format\":\"jpeg\","
                          + "\"quality\":80,"
                          + "\"pages\":[1,2]"
                          + "}}")));
    }

    @Test
    void thumbnails_png_omits_quality_and_pages_when_unset(WireMockRuntimeInfo wm) {
      stubThumbnailsOk();

      newClient(wm).documents().thumbnails(DOC_ID, ThumbnailOptions.builder().width(640).build());

      verify(
          postRequestedFor(urlEqualTo(THUMB_PATH))
              .withRequestBody(equalToJson("{\"thumbnails\":{\"width\":640,\"format\":\"png\"}}")));
    }

    @Test
    void thumbnails_uses_caller_supplied_idempotency_key(WireMockRuntimeInfo wm) {
      stubThumbnailsOk();

      ThumbnailOptions opts =
          ThumbnailOptions.builder()
              .width(640)
              .format(ThumbnailFormat.PNG)
              .idempotencyKey("thumb-key-7")
              .build();

      newClient(wm).documents().thumbnails(DOC_ID, opts);

      verify(
          postRequestedFor(urlEqualTo(THUMB_PATH))
              .withHeader(
                  "Idempotency-Key",
                  com.github.tomakehurst.wiremock.client.WireMock.equalTo("thumb-key-7")));
    }
  }

  // =================================================================
  // delete(id)
  // =================================================================

  @Nested
  class Delete {

    @Test
    void delete_sends_DELETE_to_documents_path(WireMockRuntimeInfo wm) {
      stubFor(delete(urlEqualTo(DOC_PATH)).willReturn(aResponse().withStatus(204)));

      newClient(wm).documents().delete(DOC_ID);

      verify(deleteRequestedFor(urlEqualTo(DOC_PATH)));
    }

    @Test
    void delete_returns_normally_on_200(WireMockRuntimeInfo wm) {
      stubFor(delete(urlEqualTo(DOC_PATH)).willReturn(aResponse().withStatus(200).withBody("{}")));

      // No exception → success
      newClient(wm).documents().delete(DOC_ID);
    }

    @Test
    void delete_410_throws_PoliPageGoneException(WireMockRuntimeInfo wm) {
      stubFor(
          delete(urlEqualTo(DOC_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(410)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"code\":\"GONE\",\"message\":\"already deleted\"}")));

      assertThatThrownBy(() -> newClient(wm).documents().delete(DOC_ID))
          .isInstanceOf(PoliPageGoneException.class);
    }

    @Test
    void delete_404_throws_PoliPageNotFoundException(WireMockRuntimeInfo wm) {
      stubFor(
          delete(urlEqualTo(DOC_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(404)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"code\":\"DOCUMENT_NOT_FOUND\",\"message\":\"x\"}")));

      assertThatThrownBy(() -> newClient(wm).documents().delete(DOC_ID))
          .isInstanceOf(PoliPageNotFoundException.class);
    }
  }

  // =================================================================
  // baseUrl with a path prefix
  // =================================================================

  @Nested
  class BaseUrlWithPathPrefix {

    // Why: URI.resolve("/v1/...") against a base whose path is "/prefix" follows
    // RFC 3986 and replaces the path entirely, dropping the prefix. Real users
    // hit this when the SDK is pointed at a gateway that namespaces routes.
    private static final String PREFIX = "/gw/tenant-a";
    private static final String PREFIXED_DOC_PATH = PREFIX + DOC_PATH;

    private PoliPageClient prefixedClient(WireMockRuntimeInfo wm) {
      return PoliPageClient.builder()
          .apiKey(TEST_KEY)
          .baseUrl(URI.create(wm.getHttpBaseUrl() + PREFIX))
          .maxRetries(0)
          .build();
    }

    @Test
    void get_preserves_path_prefix(WireMockRuntimeInfo wm) {
      stubFor(
          get(urlEqualTo(PREFIXED_DOC_PATH))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(DOC_JSON)));

      prefixedClient(wm).documents().get(DOC_ID);

      verify(getRequestedFor(urlEqualTo(PREFIXED_DOC_PATH)));
    }

    @Test
    void delete_preserves_path_prefix(WireMockRuntimeInfo wm) {
      stubFor(delete(urlEqualTo(PREFIXED_DOC_PATH)).willReturn(aResponse().withStatus(204)));

      prefixedClient(wm).documents().delete(DOC_ID);

      verify(deleteRequestedFor(urlEqualTo(PREFIXED_DOC_PATH)));
    }
  }
}
