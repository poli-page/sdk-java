package page.poli.sdk.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static page.poli.sdk.transport.Replies.apiError;
import static page.poli.sdk.transport.Replies.client;
import static page.poli.sdk.transport.Replies.descriptor;
import static page.poli.sdk.transport.Replies.descriptorJson;
import static page.poli.sdk.transport.Replies.fixed;
import static page.poli.sdk.transport.Replies.input;
import static page.poli.sdk.transport.Replies.serve;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.ResponseEvent;
import page.poli.sdk.RetryEvent;
import page.poli.sdk.contract.MockServer;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.model.DocumentDescriptor;

/**
 * The client's timeout bounds each attempt as a whole, the answer's body included, and the body is
 * read inside the attempt: a cut or stalled body fails that attempt (SDK spec §8.2, §9.1). The
 * upper bounds here are what the contract cannot check: it only has lower bounds.
 */
class AttemptBoundsTest {

  private static PoliPageException thrown(Runnable call) {
    try {
      call.run();
    } catch (PoliPageException e) {
      return e;
    }
    throw new AssertionError("expected a PoliPageException");
  }

  private static long millisSince(long startedNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
  }

  @Test
  void a_hung_attempt_fails_with_the_timeout_well_under_3_seconds() {
    try (MockServer server = serve(List.of(fixed(new MockServer.Hang())))) {
      PoliPageClient sdk =
          client(server).maxRetries(0).requestTimeout(Duration.ofMillis(200)).build();
      long started = System.nanoTime();

      PoliPageException e = thrown(() -> sdk.documents().get("doc_abc123"));

      assertThat(millisSince(started)).isLessThan(3_000);
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.TIMEOUT);
      assertThat(e.getMessage()).isEqualTo("Request timed out after 200ms");
    }
  }

  @Test
  void a_slow_dripping_body_cannot_outlive_the_timeout() {
    // Headers at once, then one byte every 50 ms: about 30 s for the whole body. The JDK's own
    // request timeout stops at the headers; the SDK bounds the body too.
    byte[] body = descriptorJson("http://127.0.0.1").getBytes(StandardCharsets.UTF_8);
    MockServer.Answer drip =
        new MockServer.Answer(200, Map.of("Content-Type", "application/json"), body, -1, 50);
    try (MockServer server = serve(List.of(fixed(drip)))) {
      PoliPageClient sdk =
          client(server).maxRetries(0).requestTimeout(Duration.ofMillis(300)).build();
      long started = System.nanoTime();

      PoliPageException e = thrown(() -> sdk.documents().get("doc_abc123"));

      assertThat(millisSince(started)).isLessThan(3_000);
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.TIMEOUT);
      assertThat(e.getMessage()).isEqualTo("Request timed out after 300ms");
    }
  }

  @Test
  void the_async_facade_has_the_same_bound() throws Exception {
    byte[] body = descriptorJson("http://127.0.0.1").getBytes(StandardCharsets.UTF_8);
    MockServer.Answer drip =
        new MockServer.Answer(200, Map.of("Content-Type", "application/json"), body, -1, 50);
    try (MockServer server = serve(List.of(fixed(new MockServer.Hang()), fixed(drip)))) {
      PoliPageClient sdk =
          client(server).maxRetries(1).requestTimeout(Duration.ofMillis(200)).build();
      long started = System.nanoTime();

      CompletableFuture<DocumentDescriptor> future = sdk.documentsAsync().get("doc_abc123");

      assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
          .cause()
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.getMessage()).isEqualTo("Request timed out after 200ms"));
      assertThat(millisSince(started)).isLessThan(3_000);
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void a_2xx_body_cut_mid_way_is_a_network_error_retried_and_on_response_waits_for_the_body() {
    byte[] body = descriptorJson("http://127.0.0.1").getBytes(StandardCharsets.UTF_8);
    MockServer.Answer cut =
        new MockServer.Answer(200, Map.of("Content-Type", "application/json"), body, 20, 0);
    try (MockServer server = serve(List.of(fixed(cut), descriptor()))) {
      List<RetryEvent> retries = new CopyOnWriteArrayList<>();
      List<ResponseEvent> responses = new CopyOnWriteArrayList<>();
      PoliPageClient sdk =
          client(server).maxRetries(1).onRetry(retries::add).onResponse(responses::add).build();

      assertThat(sdk.documents().get("doc_abc123").documentId()).isEqualTo("doc_abc123");

      assertThat(retries)
          .singleElement()
          .satisfies(e -> assertThat(e.reason()).isEqualTo("network_error"));
      assertThat(responses).hasSize(1);
    }
  }

  @Test
  void an_error_body_cut_mid_way_keeps_its_status_and_request_id_and_is_retried_by_it() {
    MockServer.Answer cut503 =
        new MockServer.Answer(
            503,
            Map.of("Content-Type", "application/json", "x-request-id", "req_cut"),
            "{\"error\":\"PUBLISH_IN_PROGRESS\",\"detail\":\"x\"}".getBytes(StandardCharsets.UTF_8),
            10,
            0);
    try (MockServer server = serve(List.of(fixed(cut503), fixed(cut503)))) {
      PoliPageClient sdk = client(server).maxRetries(1).build();

      PoliPageException e = thrown(() -> sdk.documents().get("doc_abc123"));

      assertThat(e.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(e.getMessage()).isEqualTo("HTTP 503");
      assertThat(e.statusCode()).isEqualTo(503);
      assertThat(e.requestId()).isEqualTo("req_cut");
      assertThat(e.getCause()).isNotNull();
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void an_error_body_cut_on_a_400_is_not_retried() {
    MockServer.Answer cut400 =
        new MockServer.Answer(
            400,
            Map.of("Content-Type", "application/json"),
            "{\"error\":\"VALIDATION_ERROR\"}".getBytes(StandardCharsets.UTF_8),
            5,
            0);
    try (MockServer server = serve(List.of(fixed(cut400), descriptor()))) {
      PoliPageException e = thrown(() -> client(server).maxRetries(2).build().documents().get("x"));

      assertThat(e.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(e.statusCode()).isEqualTo(400);
      assertThat(server.requests()).hasSize(1);
    }
  }

  @Test
  void cancelling_an_async_call_during_the_wait_sends_no_further_request() throws Exception {
    MockServer.Answer busy =
        new MockServer.Answer(
            503,
            Map.of("Content-Type", "application/json", "Retry-After", "1"),
            "{\"error\":\"PUBLISH_IN_PROGRESS\"}".getBytes(StandardCharsets.UTF_8));
    try (MockServer server = serve(List.of(fixed(busy), descriptor()))) {
      List<RetryEvent> retries = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).maxRetries(2).onRetry(retries::add).build();
      CompletableFuture<DocumentDescriptor> future = sdk.renderAsync().document(input());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (retries.isEmpty() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }

      assertThat(future.cancel(true)).isTrue();
      TimeUnit.MILLISECONDS.sleep(1_300);

      assertThat(server.requests()).hasSize(1);
    }
  }

  @Test
  void an_interrupt_during_a_blocking_attempt_aborts_at_once() throws Exception {
    try (MockServer server = serve(List.of(fixed(new MockServer.Hang()), descriptor()))) {
      PoliPageClient sdk = client(server).maxRetries(2).build();
      CompletableFuture<PoliPageException> result = new CompletableFuture<>();
      Thread caller =
          new Thread(
              () -> {
                try {
                  sdk.documents().get("doc_abc123");
                  result.complete(null);
                } catch (PoliPageException e) {
                  result.complete(e);
                }
              });
      caller.start();
      TimeUnit.MILLISECONDS.sleep(100);
      caller.interrupt();

      PoliPageException e = result.get(3, TimeUnit.SECONDS);
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.ABORTED);
      assertThat(e.getMessage()).isEqualTo("Request was aborted");
      assertThat(server.requests()).hasSize(1);
    }
  }

  @Test
  void a_quota_error_is_thrown_at_once_whatever_its_retry_after() {
    try (MockServer server =
        serve(List.of(fixed(apiError(429, "QUOTA_EXCEEDED", "req_quota")), descriptor()))) {
      long started = System.nanoTime();
      PoliPageException e =
          thrown(() -> client(server).maxRetries(2).build().render().document(input()));

      assertThat(e.code()).isEqualTo("QUOTA_EXCEEDED");
      assertThat(e.isRetryable()).isFalse();
      assertThat(millisSince(started)).isLessThan(1_000);
      assertThat(server.requests()).hasSize(1);
    }
  }
}
