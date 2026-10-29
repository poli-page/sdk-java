package page.poli.sdk.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static page.poli.sdk.transport.Replies.client;
import static page.poli.sdk.transport.Replies.descriptor;
import static page.poli.sdk.transport.Replies.fixed;
import static page.poli.sdk.transport.Replies.input;
import static page.poli.sdk.transport.Replies.serve;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.contract.MockServer;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;

/** The presigned-URL download fails with the SDK's own errors, and is never retried. */
class DownloadFailureTest {

  private static final byte[] PDF = "%PDF-1.4 fake pdf body".getBytes(StandardCharsets.US_ASCII);

  @Test
  void a_storage_503_is_download_failed_with_its_status_not_retryable_and_not_retried() {
    MockServer.Answer unavailable =
        new MockServer.Answer(
            503,
            Map.of("Content-Type", "application/xml"),
            "<Error/>".getBytes(StandardCharsets.UTF_8));
    try (MockServer server =
        serve(List.of(descriptor(), fixed(unavailable), fixed(Replies.pdf(PDF))))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).maxRetries(2).onError(errors::add).build();

      assertThatThrownBy(() -> sdk.render().pdf(input()))
          .isInstanceOfSatisfying(
              PoliPageDownloadException.class,
              e -> {
                assertThat(e.code()).isEqualTo(PoliPageErrorCode.DOWNLOAD_FAILED);
                assertThat(e.getMessage()).isEqualTo("Failed to download PDF: HTTP 503");
                assertThat(e.statusCode()).isEqualTo(503);
                assertThat(e.isRetryable()).isFalse();
                assertThat(errors).containsExactly(e);
              });
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void a_stream_cut_mid_body_raises_download_failed_from_read() throws Exception {
    MockServer.Answer cut =
        new MockServer.Answer(200, Map.of("Content-Type", "application/pdf"), PDF, 5, 0);
    try (MockServer server = serve(List.of(descriptor(), fixed(cut)))) {
      PoliPageClient sdk = client(server).build();

      try (InputStream pdf = sdk.render().pdfStream(input())) {
        assertThatThrownBy(pdf::readAllBytes)
            .isInstanceOfSatisfying(
                PoliPageDownloadException.class,
                e -> {
                  assertThat(e.code()).isEqualTo(PoliPageErrorCode.DOWNLOAD_FAILED);
                  assertThat(e.getMessage()).isEqualTo("Failed to download PDF: network error");
                  assertThat(e.statusCode()).isZero();
                  assertThat(e.getCause()).isNotNull();
                });
      }
    }
  }

  @Test
  void bytes_cut_mid_body_raise_download_failed() {
    MockServer.Answer cut =
        new MockServer.Answer(200, Map.of("Content-Type", "application/pdf"), PDF, 5, 0);
    try (MockServer server = serve(List.of(descriptor(), fixed(cut)))) {
      assertThatThrownBy(() -> client(server).build().render().pdf(input()))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.getMessage()).isEqualTo("Failed to download PDF: network error"));
    }
  }

  @Test
  void a_stalled_download_is_cut_at_the_timeout() {
    try (MockServer server = serve(List.of(descriptor(), fixed(new MockServer.Hang())))) {
      PoliPageClient sdk = client(server).requestTimeout(Duration.ofMillis(200)).build();
      long started = System.nanoTime();

      assertThatThrownBy(() -> sdk.render().pdf(input()))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.code()).isEqualTo(PoliPageErrorCode.DOWNLOAD_FAILED));
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(3_000);
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void the_async_download_maps_the_same_way() {
    try (MockServer server = serve(List.of(descriptor(), fixed(new MockServer.Drop())))) {
      assertThatThrownBy(() -> client(server).build().renderAsync().pdf(input()).join())
          .cause()
          .isInstanceOfSatisfying(
              PoliPageDownloadException.class,
              e -> assertThat(e.getMessage()).isEqualTo("Failed to download PDF: network error"));
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void a_successful_stream_and_async_stream_return_the_bytes() throws Exception {
    try (MockServer server =
        serve(
            List.of(
                descriptor(), fixed(Replies.pdf(PDF)), descriptor(), fixed(Replies.pdf(PDF))))) {
      PoliPageClient sdk = client(server).build();
      try (InputStream pdf = sdk.render().pdfStream(input())) {
        assertThat(pdf.readAllBytes()).isEqualTo(PDF);
      }
      try (InputStream pdf = sdk.renderAsync().pdfStream(input()).join()) {
        assertThat(pdf.readAllBytes()).isEqualTo(PDF);
      }
      assertThat(server.requests().get(1).header("authorization")).isNull();
    }
  }
}
