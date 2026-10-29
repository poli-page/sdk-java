package page.poli.sdk.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static page.poli.sdk.transport.Replies.client;
import static page.poli.sdk.transport.Replies.descriptor;
import static page.poli.sdk.transport.Replies.fixed;
import static page.poli.sdk.transport.Replies.input;
import static page.poli.sdk.transport.Replies.serve;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.contract.MockServer;
import page.poli.sdk.exception.PoliPageException;

/**
 * Cancelling a future of the async facades aborts the call: the work in flight stops, {@code get()}
 * / {@code join()} throw a {@code CancellationException} whose cause is the SDK's {@code aborted}
 * error, and {@code onError} fires once with it.
 */
class AsyncCancellationTest {

  private static void awaitRequests(MockServer server, int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (server.requests().size() < count && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(5);
    }
    assertThat(server.requests()).hasSize(count);
  }

  private static void assertAborted(CompletableFuture<?> future, List<Throwable> errors) {
    assertThat(future.isCancelled()).isTrue();
    assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
        .isInstanceOf(CancellationException.class)
        .cause()
        .isInstanceOf(PoliPageException.class);
    assertThatThrownBy(future::join)
        .isInstanceOf(CancellationException.class)
        .cause()
        .isInstanceOfSatisfying(
            PoliPageException.class,
            e -> {
              assertThat(e.code()).isEqualTo(PoliPageErrorCode.ABORTED);
              assertThat(e.getMessage()).isEqualTo("Request was aborted");
              assertThat(e.isRetryable()).isFalse();
              assertThat(errors).containsExactly(e);
            });
  }

  /** Cancel once the download GET arrived; the download timer must not fire afterwards. */
  private void cancelDuringDownload(Function<PoliPageClient, CompletableFuture<?>> call)
      throws Exception {
    try (MockServer server = serve(List.of(descriptor(), fixed(new MockServer.Hang())))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk =
          client(server).requestTimeout(Duration.ofMillis(400)).onError(errors::add).build();
      CompletableFuture<?> future = call.apply(sdk);
      awaitRequests(server, 2);

      assertThat(future.cancel(true)).isTrue();
      TimeUnit.MILLISECONDS.sleep(900);

      assertAborted(future, errors);
      assertThat(server.requests()).hasSize(2);
    }
  }

  @Test
  void cancelling_pdf_stream_during_the_download_cancels_the_download() throws Exception {
    cancelDuringDownload(sdk -> sdk.renderAsync().pdfStream(input()));
  }

  @Test
  void cancelling_pdf_during_the_download_cancels_the_download() throws Exception {
    cancelDuringDownload(sdk -> sdk.renderAsync().pdf(input()));
  }

  @Test
  void cancelling_render_to_file_during_the_download_cancels_the_download(@TempDir Path dir)
      throws Exception {
    Path file = dir.resolve("out.pdf");
    cancelDuringDownload(sdk -> sdk.renderToFileAsync(input(), file));

    assertThat(Files.exists(file)).isFalse();
  }

  @Test
  void cancelling_during_an_attempt_aborts_and_sends_nothing_more() throws Exception {
    try (MockServer server = serve(List.of(fixed(new MockServer.Hang()), descriptor()))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).maxRetries(2).onError(errors::add).build();
      CompletableFuture<?> future = sdk.documentsAsync().get("doc_abc123");
      awaitRequests(server, 1);

      future.cancel(true);
      TimeUnit.MILLISECONDS.sleep(200);

      assertAborted(future, errors);
      assertThat(server.requests()).hasSize(1);
    }
  }

  @Test
  void a_second_cancel_and_a_cancel_after_completion_change_nothing() {
    try (MockServer server = serve(List.of(descriptor()))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).onError(errors::add).build();
      CompletableFuture<?> done = sdk.documentsAsync().get("doc_abc123");
      done.join();

      assertThat(done.cancel(true)).isFalse();
      assertThat(done.isCancelled()).isFalse();
      assertThat(errors).isEmpty();
    }
  }

  @Test
  void stages_chained_by_the_caller_stay_plain_futures() {
    try (MockServer server = serve(List.of(descriptor()))) {
      PoliPageClient sdk = client(server).build();

      CompletableFuture<String> id =
          sdk.documentsAsync().get("doc_abc123").thenApply(d -> d.documentId());

      assertThat(id.join()).isEqualTo("doc_abc123");
      assertThat(id.getClass()).isEqualTo(CompletableFuture.class);
    }
  }

  @Test
  void a_second_cancel_keeps_the_aborted_cause_and_reports_nothing_more() throws Exception {
    try (MockServer server = serve(List.of(fixed(new MockServer.Hang())))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).onError(errors::add).build();
      CompletableFuture<?> future = sdk.documentsAsync().get("doc_abc123");
      awaitRequests(server, 1);

      assertThat(future.cancel(true)).isTrue();
      assertThat(future.cancel(true)).isTrue();
      assertThat(future.cancel(false)).isTrue();

      assertAborted(future, errors);
      assertThatThrownBy(() -> future.get())
          .isInstanceOf(CancellationException.class)
          .cause()
          .isSameAs(errors.get(0));
    }
  }

  @Test
  void concurrent_cancels_report_once() throws Exception {
    try (MockServer server = serve(List.of(fixed(new MockServer.Hang())))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).onError(errors::add).build();
      CompletableFuture<?> future = sdk.documentsAsync().get("doc_abc123");
      awaitRequests(server, 1);

      List<CompletableFuture<Boolean>> cancels = new java.util.ArrayList<>();
      for (int i = 0; i < 8; i++) {
        cancels.add(CompletableFuture.supplyAsync(() -> future.cancel(true)));
      }

      assertThat(cancels).allSatisfy(c -> assertThat(c.join()).isTrue());
      assertAborted(future, errors);
    }
  }

  @Test
  void cancelling_render_to_file_during_the_copy_stops_it_and_deletes_the_file(@TempDir Path dir)
      throws Exception {
    // 4000 bytes, one every 2 ms: the copy takes about 8 s.
    byte[] pdf = new byte[4000];
    java.util.Arrays.fill(pdf, (byte) 'x');
    MockServer.Answer drip =
        new MockServer.Answer(200, java.util.Map.of("Content-Type", "application/pdf"), pdf, -1, 2);
    try (MockServer server = serve(List.of(descriptor(), fixed(drip)))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).onError(errors::add).build();
      Path file = dir.resolve("out.pdf");
      CompletableFuture<?> future = sdk.renderToFileAsync(input(), file);
      awaitRequests(server, 2);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (!Files.exists(file) && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(5);
      }
      assertThat(file).as("the copy started").exists();

      assertThat(future.cancel(true)).isTrue();
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (server.answersCutByClient().isEmpty() && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(10);
      }
      TimeUnit.MILLISECONDS.sleep(300);

      assertThat(server.answersCutByClient())
          .as("the client closed the download")
          .containsExactly(1);
      assertThat(file).doesNotExist();
      assertAborted(future, errors);
    }
  }
}
