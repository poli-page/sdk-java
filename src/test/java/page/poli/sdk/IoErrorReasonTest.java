package page.poli.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The reason of an {@code io_error} always names the target, whatever the JDK exception names. */
class IoErrorReasonTest {

  private static final Path TARGET = Path.of("out", "invoice.pdf").toAbsolutePath();

  @Test
  void a_plain_io_error_names_the_target_and_keeps_its_message() {
    assertThat(PoliPageClient.reason(TARGET, new IOException("No space left on device")))
        .isEqualTo(TARGET + ": No space left on device");
  }

  @Test
  void an_io_error_without_a_message_gives_its_class_name() {
    assertThat(PoliPageClient.reason(TARGET, new IOException()))
        .isEqualTo(TARGET + ": IOException");
  }

  @Test
  void a_file_system_error_on_the_temporary_file_names_the_target_and_the_os_reason() {
    Path temp = TARGET.resolveSibling(".invoice.pdf.0f1e.part");

    assertThat(PoliPageClient.reason(TARGET, new AccessDeniedException(temp.toString())))
        .isEqualTo(TARGET + ": Permission denied");
    assertThat(
            PoliPageClient.reason(
                TARGET, new FileSystemException(temp.toString(), null, "Read-only file system")))
        .isEqualTo(TARGET + ": Read-only file system");
  }
}
