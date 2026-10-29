package page.poli.sdk.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static page.poli.sdk.transport.Replies.client;
import static page.poli.sdk.transport.Replies.descriptor;
import static page.poli.sdk.transport.Replies.fixed;
import static page.poli.sdk.transport.Replies.input;
import static page.poli.sdk.transport.Replies.serve;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.RequestEvent;
import page.poli.sdk.RetryEvent;
import page.poli.sdk.contract.MockServer;
import page.poli.sdk.exception.PoliPageException;

/**
 * The JDK's {@code java.net.http} client sends a request again by itself when the connection closes
 * before any byte of the answer: a {@code GET} always, any method when the JVM runs with {@code
 * -Djdk.httpclient.enableAllMethodRetry=true}. The SDK refuses those resends, so every resend is
 * its own counted retry.
 *
 * <p>Each test keeps the connection alive, then drops it on the next request, and checks how many
 * requests reached the server. The controls send the same request through a plain JDK client: they
 * prove the connection really is reused, and that without the SDK's guard the JDK resends. The
 * build runs this class a second time with {@code jdk.httpclient.enableAllMethodRetry=true}.
 */
class NoHiddenResendTest {

  private static final boolean ALL_METHOD_RETRY =
      Boolean.parseBoolean(System.getProperty("jdk.httpclient.enableAllMethodRetry", "false"));

  @Test
  void a_get_on_a_dead_reused_connection_is_a_network_error_not_a_hidden_resend() {
    try (MockServer server =
        serve(List.of(descriptor(), fixed(new MockServer.Drop()), descriptor()))) {
      PoliPageClient sdk = client(server).maxRetries(0).build();
      sdk.documents().get("doc_abc123");

      assertThatThrownBy(() -> sdk.documents().get("doc_abc123"))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.code()).isEqualTo(PoliPageErrorCode.NETWORK_ERROR));

      List<MockServer.Recorded> requests = server.requests();
      assertThat(requests).hasSize(2);
      assertThat(requests.get(1).connection())
          .as("the second request reused the first one's connection")
          .isEqualTo(requests.get(0).connection());
    }
  }

  @Test
  void a_get_dropped_on_a_fresh_connection_is_sent_once() {
    try (MockServer server = serve(List.of(fixed(new MockServer.Drop()), descriptor()))) {
      PoliPageClient sdk = client(server).maxRetries(0).build();

      assertThatThrownBy(() -> sdk.documents().get("doc_abc123"))
          .isInstanceOf(PoliPageException.class);

      assertThat(server.requests()).hasSize(1);
    }
  }

  @Test
  void control_the_jdk_resends_a_plain_get_by_itself() throws Exception {
    try (MockServer server =
        serve(List.of(descriptor(), fixed(new MockServer.Drop()), descriptor()))) {
      HttpClient http = HttpClient.newHttpClient();
      HttpRequest get =
          HttpRequest.newBuilder(URI.create(server.origin() + "/v1/documents/x")).build();
      http.send(get, HttpResponse.BodyHandlers.discarding());

      // The resend reaches the third reply, which answers: the caller never sees the drop.
      assertThat(http.send(get, HttpResponse.BodyHandlers.discarding()).statusCode())
          .isEqualTo(200);

      List<MockServer.Recorded> requests = server.requests();
      assertThat(requests).hasSize(3);
      assertThat(requests.get(1).connection()).isEqualTo(requests.get(0).connection());
    }
  }

  @Test
  void a_post_on_a_dead_reused_connection_is_a_network_error_not_a_hidden_resend() {
    try (MockServer server =
        serve(List.of(descriptor(), fixed(new MockServer.Drop()), descriptor()))) {
      PoliPageClient sdk = client(server).maxRetries(0).build();
      sdk.render().document(input());

      assertThatThrownBy(() -> sdk.render().document(input()))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.code()).isEqualTo(PoliPageErrorCode.NETWORK_ERROR));

      List<MockServer.Recorded> requests = server.requests();
      assertThat(requests).hasSize(2);
      assertThat(requests.get(1).connection()).isEqualTo(requests.get(0).connection());
    }
  }

  @Test
  void control_the_jdk_resends_a_plain_post_only_with_all_method_retry() throws Exception {
    try (MockServer server =
        serve(List.of(descriptor(), fixed(new MockServer.Drop()), descriptor()))) {
      HttpClient http = HttpClient.newHttpClient();
      HttpRequest post =
          HttpRequest.newBuilder(URI.create(server.origin() + "/v1/render"))
              .POST(HttpRequest.BodyPublishers.ofString("{}"))
              .build();
      http.send(post, HttpResponse.BodyHandlers.discarding());
      try {
        http.send(post, HttpResponse.BodyHandlers.discarding());
      } catch (java.io.IOException expectedWithoutResend) {
        // the drop reaches the caller when the JDK does not resend
      }

      List<MockServer.Recorded> requests = server.requests();
      assertThat(requests).hasSize(ALL_METHOD_RETRY ? 3 : 2);
      assertThat(requests.get(1).connection()).isEqualTo(requests.get(0).connection());
    }
  }

  @Test
  void the_sdk_retries_the_dropped_request_itself_and_counts_the_attempt() {
    try (MockServer server =
        serve(List.of(descriptor(), fixed(new MockServer.Drop()), descriptor()))) {
      List<RequestEvent> attempts = new CopyOnWriteArrayList<>();
      List<RetryEvent> retries = new CopyOnWriteArrayList<>();
      PoliPageClient sdk =
          client(server).maxRetries(1).onRequest(attempts::add).onRetry(retries::add).build();
      sdk.render().document(input());
      attempts.clear();

      sdk.render().document(input());

      assertThat(attempts).extracting(RequestEvent::attempt).containsExactly(1, 2);
      assertThat(retries)
          .singleElement()
          .satisfies(e -> assertThat(e.reason()).isEqualTo("network_error"));
      List<MockServer.Recorded> requests = server.requests();
      assertThat(requests).hasSize(3);
      assertThat(requests.get(1).header("idempotency-key"))
          .isEqualTo(requests.get(2).header("idempotency-key"));
    }
  }

  @Test
  void the_presigned_download_on_a_dead_reused_connection_is_sent_once() {
    try (MockServer server =
        serve(List.of(descriptor(), fixed(new MockServer.Drop()), descriptor()))) {
      PoliPageClient sdk = client(server).maxRetries(2).build();

      assertThatThrownBy(() -> sdk.render().pdf(input()))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> {
                assertThat(e.code()).isEqualTo(PoliPageErrorCode.DOWNLOAD_FAILED);
                assertThat(e.getMessage()).isEqualTo("Failed to download PDF: network error");
              });

      List<MockServer.Recorded> requests = server.requests();
      assertThat(requests).hasSize(2);
      assertThat(requests.get(1).target()).isEqualTo("/storage/doc.pdf");
      assertThat(requests.get(1).connection()).isEqualTo(requests.get(0).connection());
    }
  }
}
