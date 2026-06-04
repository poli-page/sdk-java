package page.poli.sdk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@WireMockTest
class HookEventsTest {

  private static final String DOC_PATH = "/v1/documents/doc_x";
  private static final String DOC_JSON =
      "{\"documentId\":\"doc_x\",\"organizationId\":\"o\",\"environment\":\"sandbox\","
          + "\"format\":\"A4\",\"pageCount\":1,\"sizeBytes\":1,"
          + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"metadata\":{},"
          + "\"presignedPdfUrl\":\"https://x\",\"expiresAt\":\"2026-01-01T00:15:00Z\"}";

  @Test
  void onRequest_fires_once_per_attempt_with_method_url_attempt(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo(DOC_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(DOC_JSON)));

    List<RequestEvent> events = new ArrayList<>();
    PoliPageClient client =
        PoliPageClient.builder()
            .apiKey("pp_test_x")
            .baseUrl(URI.create(wm.getHttpBaseUrl()))
            .maxRetries(0)
            .onRequest(events::add)
            .build();

    client.documents().get("doc_x");

    assertThat(events).hasSize(1);
    assertThat(events.get(0).method()).isEqualTo("GET");
    assertThat(events.get(0).url()).isEqualTo(wm.getHttpBaseUrl() + DOC_PATH);
    assertThat(events.get(0).attempt()).isEqualTo(1);
  }

  @Test
  void onRequest_hook_that_throws_does_not_break_request(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo(DOC_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(DOC_JSON)));

    PoliPageClient client =
        PoliPageClient.builder()
            .apiKey("pp_test_x")
            .baseUrl(URI.create(wm.getHttpBaseUrl()))
            .maxRetries(0)
            .onRequest(
                e -> {
                  throw new RuntimeException("hook bug");
                })
            .build();

    // Should not propagate the RuntimeException.
    assertThat(client.documents().get("doc_x").documentId()).isEqualTo("doc_x");
  }

  @Test
  void onResponse_fires_after_each_observed_response(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo(DOC_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("x-request-id", "req_abc")
                    .withBody(DOC_JSON)));

    List<ResponseEvent> events = new ArrayList<>();
    PoliPageClient client =
        PoliPageClient.builder()
            .apiKey("pp_test_x")
            .baseUrl(URI.create(wm.getHttpBaseUrl()))
            .maxRetries(0)
            .onResponse(events::add)
            .build();

    client.documents().get("doc_x");

    assertThat(events).hasSize(1);
    assertThat(events.get(0).status()).isEqualTo(200);
    assertThat(events.get(0).requestId()).isEqualTo("req_abc");
    assertThat(events.get(0).durationMs()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  void onResponse_fires_on_each_retry_attempt(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo(DOC_PATH))
            .inScenario("retry")
            .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
            .willReturn(aResponse().withStatus(503))
            .willSetStateTo("second"));
    stubFor(
        get(urlEqualTo(DOC_PATH))
            .inScenario("retry")
            .whenScenarioStateIs("second")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(DOC_JSON)));

    List<ResponseEvent> events = new ArrayList<>();
    PoliPageClient client =
        PoliPageClient.builder()
            .apiKey("pp_test_x")
            .baseUrl(URI.create(wm.getHttpBaseUrl()))
            .maxRetries(1)
            .retryDelay(java.time.Duration.ofMillis(1))
            .onResponse(events::add)
            .build();

    client.documents().get("doc_x");

    assertThat(events).extracting(ResponseEvent::status).containsExactly(503, 200);
  }
}
