package page.poli.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import page.poli.sdk.exception.PoliPageException;

/**
 * The connect is bounded by {@code requestTimeout} like the rest of the attempt, so a timeout
 * always reports the bound that fired: the JDK client has no connect timeout of its own.
 */
class ConnectBoundTest {

  /** A private address no route answers: the SYN is dropped, the connect hangs. */
  private static final URI BLACKHOLE = URI.create("http://10.255.255.1:81");

  @Test
  void the_http_client_has_no_connect_timeout_of_its_own() {
    assertThat(PoliPageClient.newHttpClient().connectTimeout()).isEmpty();
  }

  @Test
  void a_connect_that_hangs_times_out_at_the_request_timeout_and_says_so() {
    PoliPageClient sdk =
        PoliPageClient.builder()
            .apiKey("pp_sandbox_00000000000000000000000000000000")
            .baseUrl(BLACKHOLE)
            .maxRetries(0)
            .requestTimeout(Duration.ofMillis(1500))
            .build();
    long started = System.nanoTime();
    PoliPageException e;
    try {
      sdk.documents().get("doc_abc123");
      throw new AssertionError("expected an error");
    } catch (PoliPageException thrown) {
      e = thrown;
    }
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    Assumptions.assumeTrue(
        e.code().equals("timeout"),
        "this network refuses the blackhole address at once (" + e.code() + "): nothing to time");

    assertThat(e.getMessage()).isEqualTo("Request timed out after 1500ms");
    assertThat(elapsedMs).isBetween(1_400L, 5_000L);
  }
}
