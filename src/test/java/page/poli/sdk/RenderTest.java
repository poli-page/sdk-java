package page.poli.sdk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import page.poli.sdk.exception.PoliPageAuthException;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageGoneException;
import page.poli.sdk.exception.PoliPageNotFoundException;
import page.poli.sdk.exception.PoliPagePaymentRequiredException;
import page.poli.sdk.exception.PoliPageRateLimitException;
import page.poli.sdk.exception.PoliPageValidationException;
import page.poli.sdk.input.InlineModeInput;
import page.poli.sdk.input.ProjectModeInput;
import page.poli.sdk.model.DocumentDescriptor;
import page.poli.sdk.model.PreviewResult;

@WireMockTest
class RenderTest {

  private static final String TEST_KEY = "pp_test_secret";
  private static final byte[] PDF_BYTES =
      "%PDF-1.4\nfake pdf body".getBytes(StandardCharsets.UTF_8);
  private static final String PRESIGNED_PATH = "/presigned/abc.pdf";

  private static PoliPageClient newClient(WireMockRuntimeInfo wm) {
    // maxRetries(0) keeps Phase 2/3 tests deterministic and fast — Phase 4's RetryLoop is
    // exercised in RetryLoopTest. Retry-through-Render integration tests would go here later if
    // we want belt-and-braces coverage.
    return PoliPageClient.builder()
        .apiKey(TEST_KEY)
        .baseUrl(URI.create(wm.getHttpBaseUrl()))
        .maxRetries(0)
        .build();
  }

  private static String presignedUrl(WireMockRuntimeInfo wm) {
    return wm.getHttpBaseUrl() + PRESIGNED_PATH;
  }

  private static void stubRenderReturnsPresigned(WireMockRuntimeInfo wm) {
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"presignedPdfUrl\":\"" + presignedUrl(wm) + "\"}")));
  }

  private static void stubPresignedReturnsPdf() {
    stubFor(
        get(urlPathEqualTo(PRESIGNED_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/pdf")
                    .withBody(PDF_BYTES)));
  }

  private static ProjectModeInput simpleInput() {
    return ProjectModeInput.builder()
        .project("billing")
        .template("invoice")
        .version("1.0.0")
        .data(Map.of("invoiceNumber", "INV-001"))
        .build();
  }

  @Test
  void pdf_returns_bytes_from_presigned_url(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    byte[] pdf = newClient(wm).render().pdf(simpleInput());

    assertThat(pdf).isEqualTo(PDF_BYTES);
  }

  @Test
  void pdf_posts_to_v1_render(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm).render().pdf(simpleInput());

    verify(postRequestedFor(urlEqualTo("/v1/render")));
  }

  @Test
  void pdf_sends_authorization_bearer_header(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm).render().pdf(simpleInput());

    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withHeader("Authorization", equalTo("Bearer " + TEST_KEY)));
  }

  @Test
  void pdf_sends_content_type_application_json(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm).render().pdf(simpleInput());

    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withHeader("Content-Type", equalTo("application/json")));
  }

  @Test
  void pdf_sends_accept_application_json(WireMockRuntimeInfo wm) {
    // Wire contract: every SDK request returns JSON (the presigned-S3 hop is separate),
    // so the SDK always sends `Accept: application/json`. Matches the Node SDK behaviour.
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm).render().pdf(simpleInput());

    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withHeader("Accept", equalTo("application/json")));
  }

  @Test
  void pdf_sends_user_agent_starting_with_sdk_java(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm).render().pdf(simpleInput());

    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withHeader("User-Agent", matching("poli-page-sdk-java/.+")));
  }

  @Test
  void pdf_sends_idempotency_key_uuid_when_not_overridden(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm).render().pdf(simpleInput());

    // RFC 4122 v4 UUID lowercase, with the version nibble fixed to 4
    // and the variant nibble in {8, 9, a, b}.
    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withHeader(
                "Idempotency-Key",
                matching("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")));
  }

  @Test
  void pdf_uses_caller_supplied_idempotency_key(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    ProjectModeInput input =
        ProjectModeInput.builder()
            .project("billing")
            .template("invoice")
            .version("1.0.0")
            .data(Map.of("k", "v"))
            .idempotencyKey("user-supplied-key-abc123")
            .build();

    newClient(wm).render().pdf(input);

    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withHeader("Idempotency-Key", equalTo("user-supplied-key-abc123")));
  }

  @Test
  void pdf_body_is_exactly_project_template_version_data(WireMockRuntimeInfo wm) {
    // equalToJson is strict by default — extra fields (e.g. accidental `metadata: null`)
    // would fail the verify, so this single test also proves nothing else leaks into the body.
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm).render().pdf(simpleInput());

    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withRequestBody(
                equalToJson(
                    "{\"project\":\"billing\",\"template\":\"invoice\","
                        + "\"version\":\"1.0.0\",\"data\":{\"invoiceNumber\":\"INV-001\"}}")));
  }

  @Test
  void pdf_includes_metadata_when_set(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm)
        .render()
        .pdf(
            ProjectModeInput.builder()
                .project("billing")
                .template("invoice")
                .version("1.0.0")
                .data(Map.of("invoiceNumber", "INV-001"))
                .metadata(Map.of("customerId", "cust_123"))
                .build());

    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withRequestBody(matchingJsonPath("$.metadata.customerId", equalTo("cust_123"))));
  }

  @Test
  void pdf_omits_version_from_body_when_not_set(WireMockRuntimeInfo wm) {
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm)
        .render()
        .pdf(
            ProjectModeInput.builder()
                .project("billing")
                .template("invoice")
                .data(Map.of("k", "v"))
                .build());

    verify(
        postRequestedFor(urlEqualTo("/v1/render"))
            .withRequestBody(
                equalToJson(
                    "{\"project\":\"billing\",\"template\":\"invoice\",\"data\":{\"k\":\"v\"}}")));
  }

  @Test
  void presigned_get_omits_sdk_authorization_header(WireMockRuntimeInfo wm) {
    // Presigned S3 URLs are pre-authorized; the SDK MUST NOT add its Bearer token to that
    // request — doing so confuses some CDNs and leaks the key into S3's access logs.
    stubRenderReturnsPresigned(wm);
    stubPresignedReturnsPdf();

    newClient(wm).render().pdf(simpleInput());

    verify(getRequestedFor(urlPathEqualTo(PRESIGNED_PATH)).withHeader("Authorization", absent()));
  }

  /**
   * End-to-end error mapping through the Render facade. Pure-function mapping tests live in {@code
   * ErrorParsingTest}; these confirm the wiring inside Render.
   */
  @Nested
  class ErrorMapping {

    private void stubRenderError(int status, String code, String message) {
      stubFor(
          post(urlEqualTo("/v1/render"))
              .willReturn(
                  aResponse()
                      .withStatus(status)
                      .withHeader("Content-Type", "application/json")
                      .withHeader("x-request-id", "req_test_42")
                      .withBody("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")));
    }

    @Test
    void render_401_throws_PoliPageAuthException(WireMockRuntimeInfo wm) {
      stubRenderError(401, "INVALID_API_KEY", "bad key");

      assertThatThrownBy(() -> newClient(wm).render().pdf(simpleInput()))
          .isInstanceOf(PoliPageAuthException.class)
          .hasMessage("bad key")
          .satisfies(
              t -> {
                PoliPageAuthException ex = (PoliPageAuthException) t;
                assertThat(ex.code()).isEqualTo("INVALID_API_KEY");
                assertThat(ex.statusCode()).isEqualTo(401);
                assertThat(ex.requestId()).isEqualTo("req_test_42");
              });
    }

    @Test
    void render_402_throws_PoliPagePaymentRequiredException(WireMockRuntimeInfo wm) {
      stubRenderError(402, "PAYMENT_REQUIRED", "pay up");

      assertThatThrownBy(() -> newClient(wm).render().pdf(simpleInput()))
          .isInstanceOf(PoliPagePaymentRequiredException.class);
    }

    @Test
    void render_404_throws_PoliPageNotFoundException(WireMockRuntimeInfo wm) {
      stubRenderError(404, "DOCUMENT_NOT_FOUND", "missing");

      assertThatThrownBy(() -> newClient(wm).render().pdf(simpleInput()))
          .isInstanceOf(PoliPageNotFoundException.class)
          .satisfies(
              t ->
                  assertThat(((PoliPageNotFoundException) t).code())
                      .isEqualTo("DOCUMENT_NOT_FOUND"));
    }

    @Test
    void render_410_throws_PoliPageGoneException(WireMockRuntimeInfo wm) {
      stubRenderError(410, "GONE", "deleted");

      assertThatThrownBy(() -> newClient(wm).render().pdf(simpleInput()))
          .isInstanceOf(PoliPageGoneException.class);
    }

    @Test
    void render_400_throws_PoliPageValidationException(WireMockRuntimeInfo wm) {
      stubRenderError(400, "VALIDATION_ERROR", "no good");

      assertThatThrownBy(() -> newClient(wm).render().pdf(simpleInput()))
          .isInstanceOf(PoliPageValidationException.class);
    }

    @Test
    void render_429_throws_PoliPageRateLimitException_with_retry_after(WireMockRuntimeInfo wm) {
      stubFor(
          post(urlEqualTo("/v1/render"))
              .willReturn(
                  aResponse()
                      .withStatus(429)
                      .withHeader("Content-Type", "application/json")
                      .withHeader("Retry-After", "30")
                      .withBody("{\"code\":\"QUOTA_EXCEEDED\",\"message\":\"slow\"}")));

      assertThatThrownBy(() -> newClient(wm).render().pdf(simpleInput()))
          .isInstanceOf(PoliPageRateLimitException.class)
          .satisfies(
              t ->
                  assertThat(((PoliPageRateLimitException) t).retryAfter())
                      .isEqualTo(Duration.ofSeconds(30)));
    }

    @Test
    void render_500_throws_base_PoliPageException(WireMockRuntimeInfo wm) {
      stubRenderError(500, "INTERNAL_ERROR", "boom");

      assertThatThrownBy(() -> newClient(wm).render().pdf(simpleInput()))
          .isExactlyInstanceOf(PoliPageException.class)
          .satisfies(
              t -> {
                PoliPageException ex = (PoliPageException) t;
                assertThat(ex.code()).isEqualTo("INTERNAL_ERROR");
                assertThat(ex.statusCode()).isEqualTo(500);
                assertThat(ex.isRetryable()).isTrue();
              });
    }

    @Test
    void presigned_url_403_throws_PoliPageDownloadException(WireMockRuntimeInfo wm) {
      stubRenderReturnsPresigned(wm);
      stubFor(
          get(urlPathEqualTo(PRESIGNED_PATH))
              .willReturn(aResponse().withStatus(403).withBody("expired")));

      assertThatThrownBy(() -> newClient(wm).render().pdf(simpleInput()))
          .isInstanceOf(PoliPageDownloadException.class)
          .satisfies(
              t -> {
                PoliPageDownloadException ex = (PoliPageDownloadException) t;
                assertThat(ex.statusCode()).isEqualTo(403);
                assertThat(ex.code()).isEqualTo(PoliPageErrorCode.DOWNLOAD_FAILED);
              });
    }
  }

  /** Phase 5: {@code render().document(...)} — returns the descriptor without downloading. */
  @Nested
  class Document {

    private static final String FULL_DESCRIPTOR_JSON =
        "{"
            + "\"documentId\":\"doc_abc\","
            + "\"organizationId\":\"org_42\","
            + "\"projectId\":\"prj_1\","
            + "\"projectSlug\":\"billing\","
            + "\"templateId\":\"tpl_1\","
            + "\"templateSlug\":\"invoice\","
            + "\"version\":\"1.0.0\","
            + "\"environment\":\"sandbox\","
            + "\"apiKeyId\":\"key_test\","
            + "\"format\":\"A4\","
            + "\"orientation\":\"portrait\","
            + "\"locale\":\"en-US\","
            + "\"pageCount\":3,"
            + "\"sizeBytes\":123456,"
            + "\"createdAt\":\"2026-05-29T10:00:00Z\","
            + "\"metadata\":{\"customerId\":\"cust_1\"},"
            + "\"presignedPdfUrl\":\"https://s3.example.com/doc_abc.pdf\","
            + "\"expiresAt\":\"2026-05-29T10:15:00Z\""
            + "}";

    private void stubRenderReturnsFullDescriptor() {
      stubFor(
          post(urlEqualTo("/v1/render"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(FULL_DESCRIPTOR_JSON)));
    }

    @Test
    void document_returns_parsed_descriptor(WireMockRuntimeInfo wm) {
      stubRenderReturnsFullDescriptor();

      DocumentDescriptor d = newClient(wm).render().document(simpleInput());

      assertThat(d.documentId()).isEqualTo("doc_abc");
      assertThat(d.organizationId()).isEqualTo("org_42");
      assertThat(d.projectSlug()).isEqualTo("billing");
      assertThat(d.templateSlug()).isEqualTo("invoice");
      assertThat(d.version()).isEqualTo("1.0.0");
      assertThat(d.environment()).isEqualTo("sandbox");
      assertThat(d.format()).isEqualTo("A4");
      assertThat(d.pageCount()).isEqualTo(3);
      assertThat(d.sizeBytes()).isEqualTo(123456L);
      assertThat(d.presignedPdfUrl()).isEqualTo("https://s3.example.com/doc_abc.pdf");
      assertThat(d.metadata()).containsEntry("customerId", "cust_1");
    }

    @Test
    void document_posts_to_v1_render_and_does_not_call_presigned_url(WireMockRuntimeInfo wm) {
      stubRenderReturnsFullDescriptor();
      // Note: deliberately no stub for the presigned URL. If document() tries to fetch it,
      // WireMock 404 would surface as a download exception — the test would fail loudly.

      newClient(wm).render().document(simpleInput());

      verify(postRequestedFor(urlEqualTo("/v1/render")));
      verify(0, getRequestedFor(urlPathEqualTo("/anything")));
    }

    @Test
    void document_propagates_404_as_PoliPageNotFoundException(WireMockRuntimeInfo wm) {
      stubFor(
          post(urlEqualTo("/v1/render"))
              .willReturn(
                  aResponse()
                      .withStatus(404)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"code\":\"VERSION_NOT_FOUND\",\"message\":\"nope\"}")));

      assertThatThrownBy(() -> newClient(wm).render().document(simpleInput()))
          .isInstanceOf(PoliPageNotFoundException.class)
          .satisfies(
              t ->
                  assertThat(((PoliPageNotFoundException) t).code())
                      .isEqualTo("VERSION_NOT_FOUND"));
    }
  }

  /** Phase 5: {@code render().preview(...)} — HTML preview without storing a document. */
  @Nested
  class Preview {

    private static final String PREVIEW_JSON =
        "{\"html\":\"<html>hi</html>\",\"totalPages\":2,\"environment\":\"sandbox\"}";

    private void stubPreviewSuccess() {
      stubFor(
          post(urlEqualTo("/v1/render/preview"))
              .willReturn(
                  aResponse()
                      .withStatus(200)
                      .withHeader("Content-Type", "application/json")
                      .withBody(PREVIEW_JSON)));
    }

    @Test
    void preview_posts_to_v1_render_preview(WireMockRuntimeInfo wm) {
      stubPreviewSuccess();

      newClient(wm).render().preview(simpleInput());

      verify(postRequestedFor(urlEqualTo("/v1/render/preview")));
    }

    @Test
    void preview_returns_parsed_PreviewResult(WireMockRuntimeInfo wm) {
      stubPreviewSuccess();

      PreviewResult result = newClient(wm).render().preview(simpleInput());

      assertThat(result.html()).isEqualTo("<html>hi</html>");
      assertThat(result.totalPages()).isEqualTo(2);
      assertThat(result.environment()).isEqualTo("sandbox");
    }

    @Test
    void preview_accepts_ProjectModeInput_and_body_carries_project_template(
        WireMockRuntimeInfo wm) {
      stubPreviewSuccess();

      newClient(wm).render().preview(simpleInput());

      verify(
          postRequestedFor(urlEqualTo("/v1/render/preview"))
              .withRequestBody(
                  equalToJson(
                      "{\"project\":\"billing\",\"template\":\"invoice\","
                          + "\"version\":\"1.0.0\",\"data\":{\"invoiceNumber\":\"INV-001\"}}")));
    }

    @Test
    void preview_accepts_InlineModeInput_and_body_carries_inline_template(WireMockRuntimeInfo wm) {
      stubPreviewSuccess();

      newClient(wm)
          .render()
          .preview(
              InlineModeInput.builder()
                  .template("<html>{{name}}</html>")
                  .data(Map.of("name", "World"))
                  .build());

      // Strict equalToJson — inline-mode body must NOT carry project/version, only template+data.
      verify(
          postRequestedFor(urlEqualTo("/v1/render/preview"))
              .withRequestBody(
                  equalToJson(
                      "{\"template\":\"<html>{{name}}</html>\",\"data\":{\"name\":\"World\"}}")));
    }

    @Test
    void preview_propagates_400_as_PoliPageValidationException(WireMockRuntimeInfo wm) {
      stubFor(
          post(urlEqualTo("/v1/render/preview"))
              .willReturn(
                  aResponse()
                      .withStatus(400)
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"code\":\"VALIDATION_ERROR\",\"message\":\"bad\"}")));

      assertThatThrownBy(
              () ->
                  newClient(wm)
                      .render()
                      .preview(
                          InlineModeInput.builder().template("<p></p>").data(Map.of()).build()))
          .isInstanceOf(PoliPageValidationException.class);
    }
  }

  /** Phase 5: {@code render().pdfStream(...)} — streaming PDF download. */
  @Nested
  class PdfStream {

    @Test
    void pdfStream_returns_bytes_via_InputStream(WireMockRuntimeInfo wm) throws IOException {
      stubRenderReturnsPresigned(wm);
      stubPresignedReturnsPdf();

      try (InputStream stream = newClient(wm).render().pdfStream(simpleInput())) {
        assertThat(stream.readAllBytes()).isEqualTo(PDF_BYTES);
      }
    }

    @Test
    void pdfStream_presigned_403_throws_PoliPageDownloadException(WireMockRuntimeInfo wm) {
      stubRenderReturnsPresigned(wm);
      stubFor(
          get(urlPathEqualTo(PRESIGNED_PATH))
              .willReturn(aResponse().withStatus(403).withBody("expired")));

      assertThatThrownBy(() -> newClient(wm).render().pdfStream(simpleInput()))
          .isInstanceOf(PoliPageDownloadException.class)
          .satisfies(t -> assertThat(((PoliPageDownloadException) t).statusCode()).isEqualTo(403));
    }
  }
}
