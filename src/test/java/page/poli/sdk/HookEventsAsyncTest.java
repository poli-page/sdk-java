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
class HookEventsAsyncTest {

  private static final String DOC_PATH = "/v1/documents/doc_x";
  private static final String DOC_JSON =
      "{\"documentId\":\"doc_x\",\"organizationId\":\"o\",\"environment\":\"sandbox\","
          + "\"format\":\"A4\",\"pageCount\":1,\"sizeBytes\":1,"
          + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"metadata\":{},"
          + "\"presignedPdfUrl\":\"https://x\",\"expiresAt\":\"2026-01-01T00:15:00Z\"}";

  @Test
  void onRequest_fires_once_per_async_attempt(WireMockRuntimeInfo wm) {
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

    client.documentsAsync().get("doc_x").join();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).method()).isEqualTo("GET");
    assertThat(events.get(0).url()).isEqualTo(wm.getHttpBaseUrl() + DOC_PATH);
    assertThat(events.get(0).attempt()).isEqualTo(1);
  }

  @Test
  void onResponse_fires_on_async_observed_response(WireMockRuntimeInfo wm) {
    stubFor(
        get(urlEqualTo(DOC_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("x-request-id", "req_async_1")
                    .withBody(DOC_JSON)));

    List<ResponseEvent> events = new ArrayList<>();
    PoliPageClient client =
        PoliPageClient.builder()
            .apiKey("pp_test_x")
            .baseUrl(URI.create(wm.getHttpBaseUrl()))
            .maxRetries(0)
            .onResponse(events::add)
            .build();

    client.documentsAsync().get("doc_x").join();

    assertThat(events).hasSize(1);
    assertThat(events.get(0).status()).isEqualTo(200);
    assertThat(events.get(0).requestId()).isEqualTo("req_async_1");
  }
}
