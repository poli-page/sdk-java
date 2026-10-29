package page.poli.sdk.transport;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.contract.MockServer;
import page.poli.sdk.input.RenderInput;

/** Replies and clients shared by the transport tests. */
final class Replies {

  static final String API_KEY = "pp_sandbox_00000000000000000000000000000000";

  private Replies() {}

  /** Serve {@code steps} in arrival order; a step gets the server's origin. */
  static MockServer serve(List<Function<String, MockServer.Reply>> steps) {
    return MockServer.start(
        (index, origin) ->
            index < steps.size()
                ? steps.get(index).apply(origin)
                : apiError(599, "MOCK_EXHAUSTED", "req_exhausted"));
  }

  static Function<String, MockServer.Reply> fixed(MockServer.Reply reply) {
    return origin -> reply;
  }

  static Function<String, MockServer.Reply> descriptor() {
    return origin -> json(200, descriptorJson(origin));
  }

  static String descriptorJson(String origin) {
    return "{\"documentId\":\"doc_abc123\",\"organizationId\":\"org_xyz\",\"projectId\":null,"
               + "\"projectSlug\":\"billing\",\"templateId\":null,\"templateSlug\":\"invoice\","
               + "\"environment\":\"sandbox\",\"apiKeyId\":null,\"format\":\"A4\",\"orientation\":null,"
               + "\"locale\":null,\"pageCount\":1,\"sizeBytes\":3,\"createdAt\":\"2026-09-29T09:45:22.000Z\","
               + "\"metadata\":{},\"presignedPdfUrl\":\""
        + origin
        + "/storage/doc.pdf\",\"expiresAt\":\"2026-09-29T10:00:22.000Z\"}";
  }

  static MockServer.Answer json(int status, String body) {
    return new MockServer.Answer(
        status, Map.of("Content-Type", "application/json"), body.getBytes(StandardCharsets.UTF_8));
  }

  static MockServer.Answer apiError(int status, String code, String requestId) {
    return new MockServer.Answer(
        status,
        Map.of("Content-Type", "application/json", "x-request-id", requestId),
        ("{\"error\":\"" + code + "\",\"detail\":\"" + code + " detail\"}")
            .getBytes(StandardCharsets.UTF_8));
  }

  static MockServer.Answer pdf(byte[] bytes) {
    return new MockServer.Answer(200, Map.of("Content-Type", "application/pdf"), bytes);
  }

  static RenderInput input() {
    return RenderInput.builder()
        .project("billing")
        .template("invoice")
        .data(Map.of("invoiceNumber", "INV-001"))
        .build();
  }

  static PoliPageClient.Builder client(MockServer server) {
    return PoliPageClient.builder()
        .apiKey(API_KEY)
        .baseUrl(URI.create(server.origin()))
        .retryDelay(Duration.ofMillis(10));
  }
}
