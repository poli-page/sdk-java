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

  private static final byte[] PRE_EXISTING =
      "PRE-EXISTING".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private static MockServer.Answer drip() {
    // 4000 bytes, one every 2 ms: the copy takes 8 s or more.
    return drip(4000);
  }

  private static MockServer.Answer drip(int length) {
    byte[] pdf = new byte[length];
    java.util.Arrays.fill(pdf, (byte) 'x');
    return new MockServer.Answer(
        200, java.util.Map.of("Content-Type", "application/pdf"), pdf, -1, 2);
  }

  private static void assertUntouched(Path dir, Path file) throws Exception {
    assertThat(Files.readAllBytes(file))
        .as("the target keeps its old content")
        .isEqualTo(PRE_EXISTING);
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      assertThat(files).as("no temporary file is left").containsExactly(file);
    }
  }

  /** Cancel renderToFileAsync once {@code reached} is true, over a pre-existing target. */
  private void cancelRenderToFile(
      Path dir,
      List<java.util.function.Function<String, MockServer.Reply>> steps,
      int requests,
      long delayMs)
      throws Exception {
    Path file = dir.resolve("out.pdf");
    Files.write(file, PRE_EXISTING);
    try (MockServer server = serve(steps)) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).onError(errors::add).build();
      CompletableFuture<?> future = sdk.renderToFileAsync(input(), file);
      awaitRequests(server, requests);
      TimeUnit.MILLISECONDS.sleep(delayMs);

      assertThat(future.cancel(true)).isTrue();
      TimeUnit.MILLISECONDS.sleep(300);

      assertAborted(future, errors);
      assertUntouched(dir, file);
    }
  }

  @Test
  void cancelling_render_to_file_during_the_api_call_leaves_the_target_as_it_was(@TempDir Path dir)
      throws Exception {
    cancelRenderToFile(dir, List.of(fixed(new MockServer.Hang())), 1, 50);
  }

  @Test
  void cancelling_render_to_file_after_the_headers_before_the_body_leaves_the_target_as_it_was(
      @TempDir Path dir) throws Exception {
    MockServer.Stall headersOnly =
        new MockServer.Stall(200, java.util.Map.of("Content-Type", "application/pdf"), 4000);
    cancelRenderToFile(dir, List.of(descriptor(), fixed(headersOnly)), 2, 150);
  }

  @Test
  void cancelling_render_to_file_during_the_copy_stops_it_and_leaves_the_target_as_it_was(
      @TempDir Path dir) throws Exception {
    Path file = dir.resolve("out.pdf");
    Files.write(file, PRE_EXISTING);
    try (MockServer server = serve(List.of(descriptor(), fixed(drip())))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).onError(errors::add).build();
      CompletableFuture<?> future = sdk.renderToFileAsync(input(), file);
      awaitRequests(server, 2);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (partFiles(dir) == 0 && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(5);
      }
      assertThat(partFiles(dir)).as("the copy started").isEqualTo(1);

      assertThat(future.cancel(true)).isTrue();
      deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (server.answersCutByClient().isEmpty() && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(10);
      }
      TimeUnit.MILLISECONDS.sleep(300);

      assertThat(server.answersCutByClient())
          .as("the client closed the download")
          .containsExactly(1);
      assertAborted(future, errors);
      assertUntouched(dir, file);
    }
  }

  private static long partFiles(Path dir) throws java.io.IOException {
    try (java.util.stream.Stream<Path> files = Files.list(dir)) {
      return files.filter(f -> f.getFileName().toString().endsWith(".part")).count();
    }
  }

  @Test
  void a_download_failed_mid_body_leaves_the_target_as_it_was(@TempDir Path dir) throws Exception {
    byte[] pdf = new byte[4000];
    MockServer.Answer cut =
        new MockServer.Answer(
            200, java.util.Map.of("Content-Type", "application/pdf"), pdf, 100, 0);
    Path file = dir.resolve("out.pdf");
    Files.write(file, PRE_EXISTING);
    try (MockServer server = serve(List.of(descriptor(), fixed(cut), descriptor(), fixed(cut)))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).onError(errors::add).build();

      assertThatThrownBy(() -> sdk.renderToFileAsync(input(), file).join())
          .cause()
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.code()).isEqualTo(PoliPageErrorCode.DOWNLOAD_FAILED));
      assertUntouched(dir, file);
      assertThatThrownBy(() -> sdk.renderToFile(input(), file))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              e -> assertThat(e.code()).isEqualTo(PoliPageErrorCode.DOWNLOAD_FAILED));
      assertUntouched(dir, file);
      assertThat(errors).hasSize(2);
    }
  }

  @Test
  void a_successful_render_to_file_replaces_the_target(@TempDir Path dir) throws Exception {
    byte[] pdf = "%PDF-new".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    Path file = dir.resolve("out.pdf");
    Files.write(file, PRE_EXISTING);
    try (MockServer server =
        serve(
            List.of(
                descriptor(), fixed(Replies.pdf(pdf)), descriptor(), fixed(Replies.pdf(pdf))))) {
      PoliPageClient sdk = client(server).build();

      sdk.renderToFileAsync(input(), file).orTimeout(10, TimeUnit.SECONDS).join();
      assertThat(Files.readAllBytes(file)).isEqualTo(pdf);

      Files.write(file, PRE_EXISTING);
      sdk.renderToFile(input(), file);
      assertThat(Files.readAllBytes(file)).isEqualTo(pdf);
      try (java.util.stream.Stream<Path> files = Files.list(dir)) {
        assertThat(files).containsExactly(file);
      }
    }
  }

  @Test
  void a_future_completed_by_the_caller_is_not_an_abort(@TempDir Path dir) throws Exception {
    // orTimeout / complete() end the future without cancelling it: the copy is not stopped, the
    // written file is kept, and a real failure still fires onError.
    Path file = dir.resolve("out.pdf");
    try (MockServer server = serve(List.of(descriptor(), fixed(drip(400))))) {
      List<Throwable> errors = new CopyOnWriteArrayList<>();
      PoliPageClient sdk = client(server).onError(errors::add).build();
      CompletableFuture<Void> future = sdk.renderToFileAsync(input(), file);
      awaitRequests(server, 2);

      assertThat(future.complete(null)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
      while (!Files.exists(file) && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(50);
      }

      assertThat(file).exists();
      assertThat(Files.size(file)).isEqualTo(400);
      assertThat(errors).isEmpty();
    }
  }
}
