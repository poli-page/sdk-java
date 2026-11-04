package page.poli.sdk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageNotFoundException;
import page.poli.sdk.input.RenderInput;

@WireMockTest
class PoliPageClientFileTest {

  private static final String TEST_KEY = "pp_sandbox_secret";
  private static final byte[] PDF_BYTES =
      "%PDF-1.4\nfile pdf body".getBytes(StandardCharsets.UTF_8);
  private static final String PRESIGNED_PATH = "/presigned/file.pdf";

  private static final String FULL_DESCRIPTOR_JSON_TEMPLATE =
      "{"
          + "\"documentId\":\"doc_file\","
          + "\"organizationId\":\"org_42\","
          + "\"environment\":\"sandbox\","
          + "\"format\":\"A4\","
          + "\"pageCount\":1,"
          + "\"sizeBytes\":100,"
          + "\"createdAt\":\"2026-05-29T10:00:00Z\","
          + "\"metadata\":{},"
          + "\"presignedPdfUrl\":\"%s\","
          + "\"expiresAt\":\"2026-05-29T10:15:00Z\""
          + "}";

  private static PoliPageClient newClient(WireMockRuntimeInfo wm) {
    return PoliPageClient.builder()
        .apiKey(TEST_KEY)
        .baseUrl(URI.create(wm.getHttpBaseUrl()))
        .maxRetries(0)
        .build();
  }

  private static void stubHappyPath(WireMockRuntimeInfo wm) {
    String presigned = wm.getHttpBaseUrl() + PRESIGNED_PATH;
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(String.format(FULL_DESCRIPTOR_JSON_TEMPLATE, presigned))));
    stubFor(
        get(urlPathEqualTo(PRESIGNED_PATH))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/pdf")
                    .withBody(PDF_BYTES)));
  }

  private static RenderInput simpleInput() {
    return RenderInput.builder()
        .project("billing")
        .template("invoice")
        .data(Map.of("k", "v"))
        .build();
  }

  @Test
  void renderToFile_writes_pdf_bytes_to_target(WireMockRuntimeInfo wm, @TempDir Path tmp)
      throws IOException {
    stubHappyPath(wm);
    Path target = tmp.resolve("invoice.pdf");

    newClient(wm).renderToFile(simpleInput(), target);

    assertThat(Files.readAllBytes(target)).isEqualTo(PDF_BYTES);
  }

  @Test
  void renderToFile_overwrites_existing_file(WireMockRuntimeInfo wm, @TempDir Path tmp)
      throws IOException {
    stubHappyPath(wm);
    Path target = tmp.resolve("invoice.pdf");
    Files.writeString(target, "old contents that should be overwritten");

    newClient(wm).renderToFile(simpleInput(), target);

    assertThat(Files.readAllBytes(target)).isEqualTo(PDF_BYTES);
  }

  @Test
  void renderToFile_propagates_render_404_as_PoliPageNotFoundException(
      WireMockRuntimeInfo wm, @TempDir Path tmp) {
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"TEMPLATE_NOT_FOUND\",\"detail\":\"x\"}")));

    assertThatThrownBy(() -> newClient(wm).renderToFile(simpleInput(), tmp.resolve("nope.pdf")))
        .isInstanceOf(PoliPageNotFoundException.class);
  }

  @Test
  void renderToFile_creates_missing_parent_directories(WireMockRuntimeInfo wm, @TempDir Path tmp)
      throws IOException {
    stubHappyPath(wm);
    Path target = tmp.resolve("no/such/dir/out.pdf");

    newClient(wm).renderToFile(simpleInput(), target);

    assertThat(Files.readAllBytes(target)).isEqualTo(PDF_BYTES);
  }

  @Test
  void a_parent_that_is_a_file_is_io_error_before_any_request(
      WireMockRuntimeInfo wm, @TempDir Path tmp) throws IOException {
    stubHappyPath(wm);
    Path file = Files.writeString(tmp.resolve("not-a-dir"), "x");
    Path direct = file.resolve("out.pdf");
    Path deeper = file.resolve("sub/out.pdf");
    PoliPageClient client = newClient(wm);

    for (Path target : List.of(direct, deeper)) {
      assertThatThrownBy(() -> client.renderToFile(simpleInput(), target))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              ex -> {
                assertThat(ex.code()).isEqualTo("io_error");
                assertThat(ex.statusCode()).isZero();
                assertThat(ex.toPayload().status()).isNull();
                assertThat(ex.isRetryable()).isFalse();
                assertThat(ex.getCause()).isInstanceOf(NotDirectoryException.class);
                assertThat(ex.getMessage())
                    .isEqualTo("Failed to write PDF: " + target + ": Not a directory");
              });
    }
    assertThat(file).hasContent("x");
    WireMock.verify(0, WireMock.postRequestedFor(urlEqualTo("/v1/render")));
  }

  @Test
  void a_new_file_in_a_read_only_directory_is_io_error_before_any_request(
      WireMockRuntimeInfo wm, @TempDir Path tmp) throws IOException {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "needs POSIX permissions");
    stubHappyPath(wm);
    Path dir = Files.createDirectory(tmp.resolve("read-only"));
    Path target = dir.resolve("invoice.pdf");
    Path nested = dir.resolve("sub/invoice.pdf");
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      Assumptions.assumeFalse(Files.isWritable(dir), "permissions are not enforced (root?)");

      for (Path path : List.of(target, nested)) {
        assertThatThrownBy(() -> newClient(wm).renderToFile(simpleInput(), path))
            .isInstanceOfSatisfying(
                PoliPageException.class,
                ex ->
                    assertThat(ex.getMessage())
                        .isEqualTo("Failed to write PDF: " + path + ": Permission denied"));
      }
      try (Stream<Path> files = Files.list(dir)) {
        assertThat(files).isEmpty();
      }
      WireMock.verify(0, WireMock.postRequestedFor(urlEqualTo("/v1/render")));
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  @Test
  void renderToFile_io_error_leaves_an_existing_target_untouched(
      WireMockRuntimeInfo wm, @TempDir Path tmp) throws IOException {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "needs POSIX permissions");
    stubHappyPath(wm);
    Path dir = Files.createDirectory(tmp.resolve("read-only"));
    Path target = dir.resolve("invoice.pdf");
    byte[] before = "previous PDF".getBytes(StandardCharsets.UTF_8);
    Files.write(target, before);
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      Assumptions.assumeFalse(Files.isWritable(dir), "permissions are not enforced (root?)");

      assertThatThrownBy(() -> newClient(wm).renderToFile(simpleInput(), target))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              ex -> {
                assertThat(ex.code()).isEqualTo("io_error");
                assertThat(ex.getCause()).isInstanceOf(AccessDeniedException.class);
                assertThat(ex.getMessage())
                    .isEqualTo("Failed to write PDF: " + target + ": Permission denied");
              });
      assertThat(Files.readAllBytes(target)).isEqualTo(before);
      try (Stream<Path> files = Files.list(dir)) {
        assertThat(files).containsExactly(target);
      }
      WireMock.verify(0, WireMock.postRequestedFor(urlEqualTo("/v1/render")));
    } finally {
      Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  @Test
  void renderToFile_onto_a_directory_is_io_error_before_any_request(
      WireMockRuntimeInfo wm, @TempDir Path tmp) throws IOException {
    stubHappyPath(wm);
    Path target = Files.createDirectory(tmp.resolve("invoice.pdf"));
    Files.writeString(target.resolve("keep.txt"), "kept");

    assertThatThrownBy(() -> newClient(wm).renderToFile(simpleInput(), target))
        .isInstanceOfSatisfying(
            PoliPageException.class,
            ex -> {
              assertThat(ex.code()).isEqualTo("io_error");
              assertThat(ex.getCause()).isInstanceOf(FileSystemException.class);
              assertThat(ex.getMessage())
                  .isEqualTo("Failed to write PDF: " + target + ": not a regular file");
            });
    assertThat(target.resolve("keep.txt")).hasContent("kept");
    WireMock.verify(0, WireMock.postRequestedFor(urlEqualTo("/v1/render")));
  }

  @Test
  void renderToFile_onto_a_fifo_is_io_error_before_any_request_and_keeps_the_fifo(
      WireMockRuntimeInfo wm, @TempDir Path tmp) throws Exception {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "FIFOs are POSIX");
    stubHappyPath(wm);
    Path fifo = tmp.resolve("pipe.pdf");
    Process mkfifo;
    try {
      mkfifo = new ProcessBuilder("mkfifo", fifo.toString()).start();
    } catch (IOException noMkfifo) {
      Assumptions.abort("mkfifo is not available");
      return;
    }
    Assumptions.assumeTrue(mkfifo.waitFor() == 0, "mkfifo failed");
    PoliPageClient client = newClient(wm);

    // Opening a FIFO with no reader would block: the check reads attributes only, so this returns.
    assertThatThrownBy(() -> client.renderToFile(simpleInput(), fifo))
        .isInstanceOfSatisfying(
            PoliPageException.class,
            ex -> {
              assertThat(ex.code()).isEqualTo("io_error");
              assertThat(ex.getMessage())
                  .isEqualTo("Failed to write PDF: " + fifo + ": not a regular file");
            });
    assertThatThrownBy(() -> client.renderToFileAsync(simpleInput(), fifo).join())
        .cause()
        .isInstanceOfSatisfying(
            PoliPageException.class, ex -> assertThat(ex.code()).isEqualTo("io_error"));
    BasicFileAttributes attributes =
        Files.readAttributes(fifo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    assertThat(attributes.isOther()).as("still a FIFO").isTrue();
    WireMock.verify(0, WireMock.postRequestedFor(urlEqualTo("/v1/render")));
  }

  @Test
  void a_read_only_target_is_io_error_before_any_request_and_is_not_replaced(
      WireMockRuntimeInfo wm, @TempDir Path tmp) throws IOException {
    stubHappyPath(wm);
    Path target = tmp.resolve("invoice.pdf");
    byte[] before = "previous PDF".getBytes(StandardCharsets.UTF_8);
    Files.write(target, before);
    boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    if (posix) {
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("r--r--r--"));
    } else {
      Files.setAttribute(target, "dos:readonly", true);
    }
    try {
      Assumptions.assumeFalse(Files.isWritable(target), "permissions are not enforced (root?)");
      PoliPageClient client = newClient(wm);

      assertThatThrownBy(() -> client.renderToFile(simpleInput(), target))
          .isInstanceOfSatisfying(
              PoliPageException.class,
              ex -> {
                assertThat(ex.code()).isEqualTo("io_error");
                assertThat(ex.statusCode()).isZero();
                assertThat(ex.isRetryable()).isFalse();
                assertThat(ex.getCause()).isInstanceOf(AccessDeniedException.class);
                assertThat(ex.getMessage())
                    .isEqualTo("Failed to write PDF: " + target + ": Permission denied");
              });
      assertThatThrownBy(() -> client.renderToFileAsync(simpleInput(), target).join())
          .isInstanceOf(CompletionException.class)
          .cause()
          .isInstanceOfSatisfying(
              PoliPageException.class, ex -> assertThat(ex.code()).isEqualTo("io_error"));
      assertThat(Files.readAllBytes(target)).isEqualTo(before);
      WireMock.verify(0, WireMock.postRequestedFor(urlEqualTo("/v1/render")));
    } finally {
      if (posix) {
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"));
      } else {
        Files.setAttribute(target, "dos:readonly", false);
      }
    }
  }

  @Test
  void renderToFile_keeps_the_posix_permissions_of_an_existing_target(
      WireMockRuntimeInfo wm, @TempDir Path tmp) throws IOException {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "needs POSIX permissions");
    stubHappyPath(wm);
    Path target = tmp.resolve("invoice.pdf");
    Files.writeString(target, "old");
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r-----"));

    newClient(wm).renderToFile(simpleInput(), target);

    assertThat(Files.readAllBytes(target)).isEqualTo(PDF_BYTES);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
        .isEqualTo("rw-r-----");
  }

  @Test
  void renderToFile_through_symbolic_links_updates_the_linked_file_and_keeps_the_links(
      WireMockRuntimeInfo wm, @TempDir Path tmp) throws IOException {
    Assumptions.assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "symbolic links need privileges on Windows");
    stubHappyPath(wm);
    // sym -> real/dir, real/dir/link -> ../a.pdf: sym/link is real/a.pdf.
    Path dir = Files.createDirectories(tmp.resolve("real/dir"));
    Path sym = Files.createSymbolicLink(tmp.resolve("sym"), Path.of("real", "dir"));
    Path link = Files.createSymbolicLink(dir.resolve("link"), Path.of("..", "a.pdf"));
    Path real = tmp.resolve("real/a.pdf");
    PoliPageClient client = newClient(wm);

    // The linked file does not exist yet, then it does.
    client.renderToFile(simpleInput(), sym.resolve("link"));
    assertThat(Files.readAllBytes(real)).isEqualTo(PDF_BYTES);
    Files.writeString(real, "old");
    client.renderToFileAsync(simpleInput(), sym.resolve("link")).join();

    assertThat(Files.readAllBytes(real)).isEqualTo(PDF_BYTES);
    assertThat(Files.isSymbolicLink(link)).isTrue();
    assertThat(Files.readSymbolicLink(link)).isEqualTo(Path.of("..", "a.pdf"));
    assertThat(Files.isSymbolicLink(sym)).isTrue();
    try (Stream<Path> files = Files.list(dir)) {
      assertThat(files).containsExactly(link);
    }
    try (Stream<Path> files = Files.list(tmp.resolve("real"))) {
      assertThat(files).containsExactlyInAnyOrder(dir, real);
    }
  }

  @Test
  void renderToFileAsync_writes_pdf_bytes(WireMockRuntimeInfo wm, @TempDir Path tmp)
      throws IOException {
    stubHappyPath(wm);
    Path target = tmp.resolve("async.pdf");

    newClient(wm).renderToFileAsync(simpleInput(), target).join();

    assertThat(Files.readAllBytes(target)).isEqualTo(PDF_BYTES);
  }

  @Test
  void renderToFileAsync_wraps_render_failure_in_CompletionException(
      WireMockRuntimeInfo wm, @TempDir Path tmp) {
    stubFor(
        post(urlEqualTo("/v1/render"))
            .willReturn(
                aResponse()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"TEMPLATE_NOT_FOUND\",\"detail\":\"x\"}")));

    assertThatThrownBy(
            () -> newClient(wm).renderToFileAsync(simpleInput(), tmp.resolve("x.pdf")).join())
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(PoliPageNotFoundException.class);
  }
}
