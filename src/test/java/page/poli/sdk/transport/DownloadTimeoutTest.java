package page.poli.sdk.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static page.poli.sdk.transport.Replies.client;
import static page.poli.sdk.transport.Replies.descriptor;
import static page.poli.sdk.transport.Replies.fixed;
import static page.poli.sdk.transport.Replies.input;
import static page.poli.sdk.transport.Replies.serve;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.contract.MockServer;
import page.poli.sdk.exception.PoliPageDownloadException;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.model.DocumentDescriptor;

/**
 * The client's timeout bounds the presigned-URL download: the whole download for {@code
 * render().pdf}, and each wait for bytes (an idle timeout) for {@code render().pdfStream}. Past it,
 * the call fails with {@code DOWNLOAD_FAILED}, no status, {@code Failed to download PDF: timeout}.
 *
 * <p>The delays are real, against a local server. Each bound is several times the gaps it must let
 * through, so a slow runner does not turn a pass into a timeout.
 */
class DownloadTimeoutTest {

  private static final Duration TIMEOUT = Duration.ofMillis(400);
  private static final String TIMEOUT_MESSAGE = "Failed to download PDF: timeout";
  private static final Map<String, String> PDF_HEADERS = Map.of("Content-Type", "application/pdf");

  /** 60 bytes, one every 20 ms: about 1.2 s in all, never more than 20 ms between two bytes. */
  private static MockServer.Answer drip() {
    byte[] body = new byte[60];
    Arrays.fill(body, (byte) 'x');
    return new MockServer.Answer(200, PDF_HEADERS, body, -1, 20);
  }

  /** Headers announcing 4000 bytes, then 10 of them, then nothing. */
  private static MockServer.Stall stallAfterTenBytes() {
    byte[] prefix = new byte[10];
    Arrays.fill(prefix, (byte) 'x');
    return new MockServer.Stall(200, PDF_HEADERS, 4000, prefix);
  }

  private static Consumer<PoliPageException> isTimeout() {
    return e -> {
      assertThat(e).isInstanceOf(PoliPageDownloadException.class);
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.DOWNLOAD_FAILED);
      assertThat(e.getMessage()).isEqualTo(TIMEOUT_MESSAGE);
      assertThat(e.statusCode()).isZero();
      assertThat(e.toPayload().status()).isNull();
      assertThat(e.isRetryable()).isFalse();
    };
  }

  @Test
  void pdf_bounds_the_whole_download_even_when_bytes_keep_coming() {
    try (MockServer server =
        serve(List.of(descriptor(), fixed(drip()), descriptor(), fixed(drip())))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).onError(errors::add).build();

      assertThatThrownBy(() -> sdk.render().pdf(input()))
          .isInstanceOfSatisfying(PoliPageException.class, isTimeout());
      assertThatThrownBy(() -> sdk.renderAsync().pdf(input()).join())
          .cause()
          .isInstanceOfSatisfying(PoliPageException.class, isTimeout());

      assertThat(errors).hasSize(2);
      assertThat(server.requests()).as("never retried").hasSize(4);
    }
  }

  @Test
  void pdf_with_no_answer_is_a_timeout_not_a_network_error() {
    try (MockServer server =
        serve(
            List.of(
                descriptor(),
                fixed(new MockServer.Hang()),
                descriptor(),
                fixed(new MockServer.Hang())))) {
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).build();

      assertThatThrownBy(() -> sdk.render().pdf(input()))
          .isInstanceOfSatisfying(PoliPageException.class, isTimeout());
      assertThatThrownBy(() -> sdk.renderAsync().pdf(input()).join())
          .cause()
          .isInstanceOfSatisfying(PoliPageException.class, isTimeout());
      assertThat(server.requests()).hasSize(4);
    }
  }

  @Test
  void document_download_pdf_is_bounded_the_same_way() {
    try (MockServer server = serve(List.of(descriptor(), fixed(drip())))) {
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).build();

      DocumentDescriptor document = sdk.documents().get("doc_abc123");

      assertThatThrownBy(() -> sdk.documents().downloadPdf(document))
          .isInstanceOfSatisfying(PoliPageException.class, isTimeout());
    }
  }

  @Test
  void pdf_stream_lets_a_slow_but_steady_body_through() throws Exception {
    try (MockServer server = serve(List.of(descriptor(), fixed(drip())))) {
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).build();
      long started = System.nanoTime();

      try (InputStream pdf = sdk.render().pdfStream(input())) {
        assertThat(pdf.readAllBytes()).hasSize(60);
      }
      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .as("the whole body took longer than the timeout")
          .isGreaterThan(TIMEOUT);
    }
  }

  @Test
  void pdf_stream_does_not_count_the_time_the_caller_spends_between_reads() throws Exception {
    byte[] body = new byte[64];
    try (MockServer server = serve(List.of(descriptor(), fixed(Replies.pdf(body))))) {
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).build();

      try (InputStream pdf = sdk.render().pdfStream(input())) {
        assertThat(pdf.readNBytes(8)).hasSize(8);
        TimeUnit.MILLISECONDS.sleep(TIMEOUT.toMillis() * 2);
        assertThat(pdf.readAllBytes()).hasSize(56);
      }
    }
  }

  @Test
  void pdf_stream_fails_when_no_body_byte_arrives_after_the_headers() throws Exception {
    MockServer.Stall headersOnly = new MockServer.Stall(200, PDF_HEADERS, 4000);
    try (MockServer server =
        serve(List.of(descriptor(), fixed(headersOnly), descriptor(), fixed(headersOnly)))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).onError(errors::add).build();

      try (InputStream pdf = sdk.render().pdfStream(input())) {
        assertThatThrownBy(pdf::read).isInstanceOfSatisfying(PoliPageException.class, isTimeout());
      }
      try (InputStream pdf = sdk.renderAsync().pdfStream(input()).join()) {
        assertThatThrownBy(pdf::readAllBytes)
            .isInstanceOfSatisfying(PoliPageException.class, isTimeout());
      }
      assertThat(errors).hasSize(2);
    }
  }

  @Test
  void pdf_stream_fails_when_the_body_stops_mid_way() throws Exception {
    try (MockServer server = serve(List.of(descriptor(), fixed(stallAfterTenBytes())))) {
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).build();

      try (InputStream pdf = sdk.render().pdfStream(input())) {
        assertThat(pdf.readNBytes(10)).hasSize(10);
        long started = System.nanoTime();
        assertThatThrownBy(pdf::read).isInstanceOfSatisfying(PoliPageException.class, isTimeout());
        assertThat(Duration.ofNanos(System.nanoTime() - started))
            .as("the read waited for the timeout")
            .isGreaterThanOrEqualTo(TIMEOUT);
        // Once timed out, the stream never reports a clean end.
        assertThatThrownBy(pdf::read).isInstanceOfSatisfying(PoliPageException.class, isTimeout());
      }
    }
  }

  @Test
  void a_body_shorter_than_its_content_length_is_a_network_error_not_a_timeout() throws Exception {
    MockServer.Answer cut = new MockServer.Answer(200, PDF_HEADERS, new byte[4000], 100, 0);
    try (MockServer server = serve(List.of(descriptor(), fixed(cut), descriptor(), fixed(cut)))) {
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).build();

      try (InputStream pdf = sdk.render().pdfStream(input())) {
        assertThatThrownBy(pdf::readAllBytes)
            .isInstanceOfSatisfying(
                PoliPageException.class,
                e -> assertThat(e.getMessage()).isEqualTo("Failed to download PDF: network error"));
      }
      assertThatThrownBy(() -> sdk.render().pdf(input()))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.getMessage()).isEqualTo("Failed to download PDF: network error"));
    }
  }

  @Test
  void render_to_file_leaves_the_target_untouched_when_the_body_stops(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("out.pdf");
    byte[] before = "previous PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    Files.write(file, before);
    Path missing = dir.resolve("missing.pdf");
    try (MockServer server =
        serve(
            List.of(
                descriptor(),
                fixed(stallAfterTenBytes()),
                descriptor(),
                fixed(stallAfterTenBytes())))) {
      PoliPageClient sdk = client(server).requestTimeout(TIMEOUT).build();

      assertThatThrownBy(() -> sdk.renderToFile(input(), file))
          .isInstanceOfSatisfying(PoliPageException.class, isTimeout());
      assertThatThrownBy(() -> sdk.renderToFileAsync(input(), missing).join())
          .cause()
          .isInstanceOfSatisfying(PoliPageException.class, isTimeout());
    }
    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files).containsExactly(file);
    }
  }
}
