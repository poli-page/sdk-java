package page.poli.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.RetryEvent;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageNetworkException;

class RetryLoopTest {

  private static final Duration BASE_DELAY = Duration.ofMillis(100);

  private RecordingSleeper sleeper;
  private Backoff deterministicBackoff;

  @BeforeEach
  void setup() {
    sleeper = new RecordingSleeper();
    deterministicBackoff = new Backoff(() -> 1.0);
  }

  private RetryLoop newLoop(int maxRetries) {
    return new RetryLoop(maxRetries, BASE_DELAY, deterministicBackoff, sleeper);
  }

  // -- happy path ------------------------------------------------------

  @Test
  void success_on_first_attempt_no_sleeps() {
    FakeCall call = new FakeCall().response(200, Map.of());

    HttpResponse<byte[]> resp = newLoop(2).execute(call, "POST /x");

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(call.callCount).isEqualTo(1);
    assertThat(sleeper.sleeps).isEmpty();
  }

  // -- retryable status codes -----------------------------------------

  @Nested
  class RetryableStatuses {

    @Test
    void retries_on_500_then_returns_success() {
      FakeCall call = new FakeCall().response(500, Map.of()).response(200, Map.of());

      HttpResponse<byte[]> resp = newLoop(2).execute(call, "POST /x");

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(call.callCount).isEqualTo(2);
      assertThat(sleeper.sleeps).hasSize(1);
      assertThat(sleeper.sleeps.get(0)).isEqualTo(BASE_DELAY); // attempt 0 × jitter 1
    }

    @Test
    void retries_on_502_503_504() {
      for (int status : new int[] {502, 503, 504}) {
        sleeper.sleeps.clear();
        FakeCall call = new FakeCall().response(status, Map.of()).response(200, Map.of());
        newLoop(2).execute(call, "POST /x");
        assertThat(call.callCount).isEqualTo(2);
      }
    }

    @Test
    void retries_on_429_then_returns_success() {
      FakeCall call = new FakeCall().response(429, Map.of()).response(200, Map.of());

      HttpResponse<byte[]> resp = newLoop(2).execute(call, "POST /x");

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(call.callCount).isEqualTo(2);
    }

    @Test
    void returns_last_5xx_when_retries_exhausted() {
      FakeCall call =
          new FakeCall().response(500, Map.of()).response(500, Map.of()).response(500, Map.of());

      HttpResponse<byte[]> resp = newLoop(2).execute(call, "POST /x");

      assertThat(resp.statusCode()).isEqualTo(500);
      assertThat(call.callCount).isEqualTo(3);
      assertThat(sleeper.sleeps).hasSize(2);
    }
  }

  // -- non-retryable status codes -------------------------------------

  @Nested
  class NeverRetryClientErrors {

    @Test
    void status_400_returned_after_single_attempt() {
      FakeCall call = new FakeCall().response(400, Map.of());

      HttpResponse<byte[]> resp = newLoop(5).execute(call, "POST /x");

      assertThat(resp.statusCode()).isEqualTo(400);
      assertThat(call.callCount).isEqualTo(1);
      assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    void status_401_403_404_410_422_all_non_retryable() {
      for (int status : new int[] {401, 403, 404, 410, 422}) {
        FakeCall call = new FakeCall().response(status, Map.of());
        sleeper.sleeps.clear();
        newLoop(5).execute(call, "POST /x");
        assertThat(call.callCount).isEqualTo(1);
        assertThat(sleeper.sleeps).isEmpty();
      }
    }
  }

  // -- Retry-After header --------------------------------------------

  @Nested
  class RetryAfter {

    @Test
    void honors_retry_after_seconds_below_cap() {
      FakeCall call =
          new FakeCall().response(429, Map.of("retry-after", "5")).response(200, Map.of());

      newLoop(2).execute(call, "POST /x");

      assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(5));
    }

    @Test
    void caps_retry_after_at_30_seconds() {
      FakeCall call =
          new FakeCall().response(429, Map.of("retry-after", "120")).response(200, Map.of());

      newLoop(2).execute(call, "POST /x");

      assertThat(sleeper.sleeps).containsExactly(Duration.ofSeconds(30));
    }

    @Test
    void zero_seconds_retry_after_yields_zero_sleep() {
      FakeCall call =
          new FakeCall().response(429, Map.of("retry-after", "0")).response(200, Map.of());

      newLoop(2).execute(call, "POST /x");

      assertThat(sleeper.sleeps).containsExactly(Duration.ZERO);
    }

    @Test
    void absent_retry_after_falls_back_to_exponential_backoff() {
      FakeCall call = new FakeCall().response(500, Map.of()).response(200, Map.of());

      newLoop(2).execute(call, "POST /x");

      // attempt 0 with deterministic jitter 1.0 → BASE_DELAY × 2^0 × 1.0 = BASE_DELAY
      assertThat(sleeper.sleeps).containsExactly(BASE_DELAY);
    }

    @Test
    void unparseable_retry_after_falls_back_to_exponential_backoff() {
      FakeCall call =
          new FakeCall().response(429, Map.of("retry-after", "soon")).response(200, Map.of());

      newLoop(2).execute(call, "POST /x");

      assertThat(sleeper.sleeps).containsExactly(BASE_DELAY);
    }
  }

  // -- transport exceptions ------------------------------------------

  @Nested
  class TransportExceptions {

    @Test
    void retries_on_IOException_then_succeeds() {
      FakeCall call = new FakeCall().ioException(new IOException("reset")).response(200, Map.of());

      HttpResponse<byte[]> resp = newLoop(2).execute(call, "POST /x");

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(call.callCount).isEqualTo(2);
      assertThat(sleeper.sleeps).hasSize(1);
    }

    @Test
    void retries_on_HttpTimeoutException_then_succeeds() {
      FakeCall call =
          new FakeCall().ioException(new HttpTimeoutException("slow")).response(200, Map.of());

      HttpResponse<byte[]> resp = newLoop(2).execute(call, "POST /x");

      assertThat(resp.statusCode()).isEqualTo(200);
    }

    @Test
    void throws_PoliPageNetworkException_when_IOException_exhausted() {
      FakeCall call =
          new FakeCall()
              .ioException(new IOException("reset 1"))
              .ioException(new IOException("reset 2"))
              .ioException(new IOException("reset 3"));

      assertThatThrownBy(() -> newLoop(2).execute(call, "POST /x"))
          .isInstanceOf(PoliPageNetworkException.class)
          .satisfies(
              t -> {
                PoliPageNetworkException ex = (PoliPageNetworkException) t;
                assertThat(ex.code()).isEqualTo(PoliPageErrorCode.NETWORK_ERROR);
                assertThat(ex.getCause()).hasMessageContaining("reset 3");
              });
      assertThat(call.callCount).isEqualTo(3);
    }

    @Test
    void throws_timeout_code_when_timeout_exhausted() {
      FakeCall call =
          new FakeCall()
              .ioException(new HttpTimeoutException("a"))
              .ioException(new HttpTimeoutException("b"))
              .ioException(new HttpTimeoutException("c"));

      assertThatThrownBy(() -> newLoop(2).execute(call, "POST /x"))
          .isInstanceOf(PoliPageNetworkException.class)
          .satisfies(
              t ->
                  assertThat(((PoliPageNetworkException) t).code())
                      .isEqualTo(PoliPageErrorCode.TIMEOUT));
    }
  }

  // -- interruption --------------------------------------------------

  @Nested
  class Interruption {

    @Test
    void InterruptedException_during_call_throws_ABORTED_no_retry() {
      FakeCall call = new FakeCall().interrupted();

      assertThatThrownBy(() -> newLoop(5).execute(call, "POST /x"))
          .isInstanceOf(PoliPageException.class)
          .satisfies(
              t -> assertThat(((PoliPageException) t).code()).isEqualTo(PoliPageErrorCode.ABORTED));
      assertThat(call.callCount).isEqualTo(1);
      assertThat(Thread.interrupted()).isTrue(); // clears the flag for subsequent tests
    }

    @Test
    void InterruptedException_during_sleep_aborts() {
      sleeper.throwOnNextSleep = true;
      FakeCall call = new FakeCall().response(500, Map.of()).response(200, Map.of());

      assertThatThrownBy(() -> newLoop(2).execute(call, "POST /x"))
          .isInstanceOf(PoliPageException.class)
          .satisfies(
              t -> assertThat(((PoliPageException) t).code()).isEqualTo(PoliPageErrorCode.ABORTED));
      assertThat(Thread.interrupted()).isTrue();
    }
  }

  // -- maxRetries=0 --------------------------------------------------

  @Test
  void max_retries_zero_makes_one_attempt_only_for_5xx() {
    FakeCall call = new FakeCall().response(500, Map.of());

    HttpResponse<byte[]> resp = newLoop(0).execute(call, "POST /x");

    assertThat(resp.statusCode()).isEqualTo(500);
    assertThat(call.callCount).isEqualTo(1);
    assertThat(sleeper.sleeps).isEmpty();
  }

  @Test
  void max_retries_zero_makes_one_attempt_only_for_IOException() {
    FakeCall call = new FakeCall().ioException(new IOException("x"));

    assertThatThrownBy(() -> newLoop(0).execute(call, "POST /x"))
        .isInstanceOf(PoliPageNetworkException.class);
    assertThat(call.callCount).isEqualTo(1);
  }

  // -- constructor ---------------------------------------------------

  @Test
  void constructor_rejects_negative_maxRetries() {
    assertThatThrownBy(() -> new RetryLoop(-1, BASE_DELAY, deterministicBackoff, sleeper))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // -- hooks ---------------------------------------------------------

  @Nested
  class Hooks {

    private RetryLoop loopWith(
        int maxRetries,
        @Nullable Consumer<RetryEvent> onRetry,
        @Nullable Consumer<Throwable> onError) {
      return new RetryLoop(maxRetries, BASE_DELAY, deterministicBackoff, sleeper, onRetry, onError);
    }

    @Test
    void onRetry_fires_once_per_5xx_retry_with_5xx_reason() {
      List<RetryEvent> events = new ArrayList<>();
      FakeCall call = new FakeCall().response(500, Map.of()).response(200, Map.of());

      loopWith(2, events::add, null).execute(call, "POST /x");

      assertThat(events).hasSize(1);
      assertThat(events.get(0).attempt()).isZero();
      assertThat(events.get(0).statusCode()).isEqualTo(500);
      assertThat(events.get(0).reason()).isEqualTo("5xx");
      assertThat(events.get(0).delay()).isEqualTo(BASE_DELAY);
    }

    @Test
    void onRetry_uses_rate_limit_reason_for_429() {
      List<RetryEvent> events = new ArrayList<>();
      FakeCall call = new FakeCall().response(429, Map.of()).response(200, Map.of());

      loopWith(2, events::add, null).execute(call, "POST /x");

      assertThat(events.get(0).reason()).isEqualTo("rate_limit");
      assertThat(events.get(0).statusCode()).isEqualTo(429);
    }

    @Test
    void onRetry_sets_null_statusCode_for_IOException_with_network_error_reason() {
      List<RetryEvent> events = new ArrayList<>();
      FakeCall call = new FakeCall().ioException(new IOException("reset")).response(200, Map.of());

      loopWith(2, events::add, null).execute(call, "POST /x");

      assertThat(events.get(0).statusCode()).isNull();
      assertThat(events.get(0).reason()).isEqualTo("network_error");
    }

    @Test
    void onRetry_sets_timeout_reason_for_HttpTimeoutException() {
      List<RetryEvent> events = new ArrayList<>();
      FakeCall call =
          new FakeCall().ioException(new HttpTimeoutException("slow")).response(200, Map.of());

      loopWith(2, events::add, null).execute(call, "POST /x");

      assertThat(events.get(0).reason()).isEqualTo("timeout");
      assertThat(events.get(0).statusCode()).isNull();
    }

    @Test
    void onError_fires_when_IOException_retries_exhausted() {
      List<Throwable> errors = new ArrayList<>();
      FakeCall call =
          new FakeCall().ioException(new IOException("a")).ioException(new IOException("b"));

      assertThatThrownBy(() -> loopWith(1, null, errors::add).execute(call, "POST /x"))
          .isInstanceOf(PoliPageNetworkException.class);

      assertThat(errors).hasSize(1);
      assertThat(errors.get(0)).isInstanceOf(PoliPageNetworkException.class);
    }

    @Test
    void onError_does_not_fire_on_InterruptedException() {
      List<Throwable> errors = new ArrayList<>();
      FakeCall call = new FakeCall().interrupted();

      assertThatThrownBy(() -> loopWith(2, null, errors::add).execute(call, "POST /x"))
          .isInstanceOf(PoliPageException.class)
          .satisfies(
              t -> assertThat(((PoliPageException) t).code()).isEqualTo(PoliPageErrorCode.ABORTED));

      assertThat(errors).isEmpty(); // ← key invariant
      assertThat(Thread.interrupted()).isTrue();
    }

    @Test
    void onError_does_not_fire_when_5xx_is_returned_after_exhaustion() {
      // Per the loop's contract, exhausted-but-retryable status returns the response so the
      // caller can run ErrorParsing — no terminal "throw" inside the loop, no onError.
      List<Throwable> errors = new ArrayList<>();
      FakeCall call = new FakeCall().response(500, Map.of()).response(500, Map.of());

      HttpResponse<byte[]> resp = loopWith(1, null, errors::add).execute(call, "POST /x");

      assertThat(resp.statusCode()).isEqualTo(500);
      assertThat(errors).isEmpty();
    }

    @Test
    void faulty_onRetry_hook_does_not_break_the_request() {
      java.util.function.Consumer<page.poli.sdk.RetryEvent> faulty =
          e -> {
            throw new RuntimeException("hook blew up");
          };
      FakeCall call = new FakeCall().response(500, Map.of()).response(200, Map.of());

      HttpResponse<byte[]> resp = loopWith(2, faulty, null).execute(call, "POST /x");

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(call.callCount).isEqualTo(2);
    }

    @Test
    void faulty_onError_hook_does_not_swallow_the_thrown_exception() {
      java.util.function.Consumer<Throwable> faulty =
          err -> {
            throw new RuntimeException("hook blew up");
          };
      FakeCall call = new FakeCall().ioException(new IOException("x"));

      assertThatThrownBy(() -> loopWith(0, null, faulty).execute(call, "POST /x"))
          .isInstanceOf(PoliPageNetworkException.class);
    }
  }

  // ===== Test doubles ===============================================

  /**
   * Programmable transport call. Pre-load a sequence of responses / exceptions; each invocation
   * pulls the next entry.
   */
  private static final class FakeCall implements RetryLoop.IoCall {
    private final Deque<Object> queue = new ArrayDeque<>();
    int callCount = 0;

    FakeCall response(int status, Map<String, String> headers) {
      queue.add(new FakeResponse(status, headers));
      return this;
    }

    FakeCall ioException(IOException e) {
      queue.add(e);
      return this;
    }

    FakeCall interrupted() {
      queue.add(new InterruptedException("test interrupt"));
      return this;
    }

    @Override
    public HttpResponse<byte[]> send() throws IOException, InterruptedException {
      callCount++;
      Object next = queue.poll();
      if (next == null) {
        throw new AssertionError(
            "FakeCall ran out of pre-loaded responses on attempt " + callCount);
      }
      if (next instanceof HttpResponse<?> r) {
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> typed = (HttpResponse<byte[]>) r;
        return typed;
      }
      if (next instanceof IOException ioe) {
        throw ioe;
      }
      if (next instanceof InterruptedException ie) {
        throw ie;
      }
      throw new AssertionError("FakeCall got unexpected entry: " + next.getClass());
    }
  }

  /** Recording sleeper — captures requested durations without actually sleeping. */
  private static final class RecordingSleeper implements Sleeper {
    final List<Duration> sleeps = new ArrayList<>();
    boolean throwOnNextSleep = false;

    @Override
    public void sleep(Duration duration) throws InterruptedException {
      if (throwOnNextSleep) {
        throwOnNextSleep = false;
        throw new InterruptedException("test interrupt during sleep");
      }
      sleeps.add(duration);
    }
  }

  /** Minimal {@link HttpResponse} stand-in for the two fields RetryLoop reads. */
  private static final class FakeResponse implements HttpResponse<byte[]> {
    private final int status;
    private final HttpHeaders headers;

    FakeResponse(int status, Map<String, String> headers) {
      this.status = status;
      Map<String, List<String>> map = new HashMap<>();
      headers.forEach((k, v) -> map.put(k, List.of(v)));
      this.headers = HttpHeaders.of(map, (k, v) -> true);
    }

    @Override
    public int statusCode() {
      return status;
    }

    @Override
    public HttpHeaders headers() {
      return headers;
    }

    @Override
    public byte[] body() {
      return new byte[0];
    }

    @Override
    public HttpRequest request() {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<HttpResponse<byte[]>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      throw new UnsupportedOperationException();
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
