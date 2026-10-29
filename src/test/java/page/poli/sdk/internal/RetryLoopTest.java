package page.poli.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.ResponseInfo;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageErrorCode;
import page.poli.sdk.RequestEvent;
import page.poli.sdk.ResponseEvent;
import page.poli.sdk.RetryEvent;
import page.poli.sdk.exception.PoliPageException;
import page.poli.sdk.exception.PoliPageNetworkException;
import page.poli.sdk.exception.PoliPageRateLimitException;

class RetryLoopTest {

  private static final Duration BASE_DELAY = Duration.ofMillis(100);
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final URI URL = URI.create("https://api.test/v1/render");

  private RecordingSleeper sleeper;
  private final List<RetryEvent> retries = new CopyOnWriteArrayList<>();
  private final List<RequestEvent> requests = new CopyOnWriteArrayList<>();
  private final List<ResponseEvent> responses = new CopyOnWriteArrayList<>();
  private final List<Throwable> errors = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setup() {
    sleeper = new RecordingSleeper();
  }

  private RetryLoop newLoop(int maxRetries) {
    return newLoop(maxRetries, TIMEOUT);
  }

  private RetryLoop newLoop(int maxRetries, Duration timeout) {
    return new RetryLoop(
        maxRetries,
        BASE_DELAY,
        timeout,
        new Backoff(() -> 1.0),
        sleeper,
        new ObjectMapper(),
        new Hooks(requests::add, responses::add, retries::add, errors::add));
  }

  private static PoliPageException thrown(Runnable call) {
    try {
      call.run();
    } catch (PoliPageException e) {
      return e;
    }
    throw new AssertionError("expected a PoliPageException");
  }

  private static String apiError(String code) {
    return "{\"error\":\"" + code + "\",\"detail\":\"" + code + " detail\"}";
  }

  @Test
  void success_on_first_attempt_no_sleeps() {
    FakeAttempt call = new FakeAttempt().respond(200, Map.of("x-request-id", "req_1"), "{}");

    HttpResponse<byte[]> resp = newLoop(2).execute(call, "POST", URL);

    assertThat(resp.statusCode()).isEqualTo(200);
    assertThat(call.sent).isEqualTo(1);
    assertThat(sleeper.sleeps).isEmpty();
    assertThat(requests).extracting(RequestEvent::attempt).containsExactly(1);
    assertThat(responses)
        .singleElement()
        .satisfies(e -> assertThat(e.requestId()).isEqualTo("req_1"));
    assertThat(errors).isEmpty();
  }

  @Test
  void constructor_rejects_negative_maxRetries() {
    assertThatThrownBy(() -> newLoop(-1)).isInstanceOf(IllegalArgumentException.class);
  }

  @Nested
  class RetryableAnswers {

    @Test
    void retries_5xx_then_returns_success() {
      FakeAttempt call =
          new FakeAttempt()
              .respond(500, Map.of(), apiError("INTERNAL_ERROR"))
              .respond(503, Map.of(), apiError("ORGANIZATION_MIGRATING"))
              .respond(200, Map.of(), "{}");

      assertThat(newLoop(2).execute(call, "POST", URL).statusCode()).isEqualTo(200);

      assertThat(call.sent).isEqualTo(3);
      assertThat(sleeper.sleeps).containsExactly(Duration.ofMillis(100), Duration.ofMillis(200));
      assertThat(requests).extracting(RequestEvent::attempt).containsExactly(1, 2, 3);
      assertThat(retries).extracting(RetryEvent::attempt).containsExactly(2, 3);
      assertThat(retries).extracting(RetryEvent::reason).containsExactly("5xx", "5xx");
      assertThat(retries).extracting(RetryEvent::statusCode).containsExactly(500, 503);
      assertThat(responses).extracting(ResponseEvent::status).containsExactly(200);
    }

    @Test
    void retries_429_rate_limit_with_rate_limit_reason() {
      FakeAttempt call =
          new FakeAttempt()
              .respond(429, Map.of(), apiError("RATE_LIMIT_EXCEEDED"))
              .respond(200, Map.of(), "{}");

      newLoop(2).execute(call, "POST", URL);

      assertThat(retries)
          .singleElement()
          .satisfies(e -> assertThat(e.reason()).isEqualTo("rate_limit"));
    }

    @Test
    void throws_the_last_error_with_its_request_id_when_retries_are_exhausted() {
      FakeAttempt call =
          new FakeAttempt()
              .respond(503, Map.of("x-request-id", "req_1"), apiError("ORGANIZATION_MIGRATING"))
              .respond(503, Map.of("x-request-id", "req_2"), apiError("ORGANIZATION_MIGRATING"))
              .respond(503, Map.of("x-request-id", "req_3"), apiError("ORGANIZATION_MIGRATING"));

      PoliPageException e = thrown(() -> newLoop(2).execute(call, "DELETE", URL));

      assertThat(e.code()).isEqualTo("ORGANIZATION_MIGRATING");
      assertThat(e.statusCode()).isEqualTo(503);
      assertThat(e.requestId()).isEqualTo("req_3");
      assertThat(e.isRetryable()).isTrue();
      assertThat(call.sent).isEqualTo(3);
      assertThat(errors).containsExactly(e);
    }

    @Test
    void max_retries_zero_makes_one_attempt() {
      FakeAttempt call = new FakeAttempt().respond(500, Map.of(), apiError("INTERNAL_ERROR"));

      assertThat(thrown(() -> newLoop(0).execute(call, "POST", URL)).code())
          .isEqualTo("INTERNAL_ERROR");
      assertThat(call.sent).isEqualTo(1);
      assertThat(retries).isEmpty();
    }
  }

  @Nested
  class NotRetried {

    @Test
    void client_errors_are_thrown_after_one_attempt() {
      for (int status : new int[] {400, 401, 403, 404, 410, 413, 422}) {
        FakeAttempt call = new FakeAttempt().respond(status, Map.of(), apiError("SOME_CODE"));

        PoliPageException e = thrown(() -> newLoop(2).execute(call, "POST", URL));

        assertThat(e.statusCode()).isEqualTo(status);
        assertThat(e.code()).isEqualTo("SOME_CODE");
        assertThat(call.sent).as("status %d", status).isEqualTo(1);
      }
      assertThat(sleeper.sleeps).isEmpty();
      assertThat(errors).hasSize(7);
    }

    @Test
    void quota_overage_and_storage_access_codes_are_never_retried() {
      for (String code :
          List.of("QUOTA_EXCEEDED", "OVERAGE_CAP_EXCEEDED", "STORAGE_ACCESS_DENIED")) {
        int status = code.equals("STORAGE_ACCESS_DENIED") ? 502 : 429;
        FakeAttempt call =
            new FakeAttempt()
                .respond(status, Map.of("Retry-After", "3600"), apiError(code))
                .respond(200, Map.of(), "{}");

        PoliPageException e = thrown(() -> newLoop(2).execute(call, "POST", URL));

        assertThat(e.code()).isEqualTo(code);
        assertThat(e.isRetryable()).isFalse();
        assertThat(call.sent).as(code).isEqualTo(1);
      }
      assertThat(sleeper.sleeps).isEmpty();
      assertThat(retries).isEmpty();
    }
  }

  @Nested
  class RetryAfter {

    private Duration firstDelay(Map<String, String> headers) {
      FakeAttempt call =
          new FakeAttempt()
              .respond(503, headers, apiError("PUBLISH_IN_PROGRESS"))
              .respond(200, Map.of(), "{}");
      newLoop(1).execute(call, "POST", URL);
      assertThat(retries)
          .singleElement()
          .satisfies(e -> assertThat(e.delay()).isEqualTo(sleeper.sleeps.get(0)));
      return sleeper.sleeps.get(0);
    }

    @Test
    void integer_seconds_set_the_delay_without_jitter() {
      assertThat(firstDelay(Map.of("Retry-After", "2"))).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void is_clamped_to_30_seconds() {
      assertThat(firstDelay(Map.of("Retry-After", "120"))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void a_huge_integer_is_clamped_to_30_seconds() {
      assertThat(firstDelay(Map.of("Retry-After", "99999999999999999999999999999")))
          .isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void zero_retries_at_once() {
      assertThat(firstDelay(Map.of("Retry-After", "0"))).isEqualTo(Duration.ZERO);
    }

    @Test
    void a_past_http_date_retries_at_once() {
      assertThat(firstDelay(Map.of("Retry-After", "Wed, 21 Oct 2015 07:28:00 GMT")))
          .isEqualTo(Duration.ZERO);
    }

    @Test
    void absent_falls_back_to_the_backoff() {
      assertThat(firstDelay(Map.of())).isEqualTo(BASE_DELAY);
    }

    @Test
    void unparseable_falls_back_to_the_backoff() {
      assertThat(firstDelay(Map.of("Retry-After", "soon"))).isEqualTo(BASE_DELAY);
      sleeper.sleeps.clear();
      retries.clear();
      assertThat(firstDelay(Map.of("Retry-After", "-1"))).isEqualTo(BASE_DELAY);
    }

    @Test
    void retry_after_ms_is_ignored() {
      assertThat(firstDelay(Map.of("Retry-After-Ms", "5000"))).isEqualTo(BASE_DELAY);
    }

    @Test
    void the_backoff_doubles_from_one_retry_to_the_next() {
      FakeAttempt call =
          new FakeAttempt()
              .fail(new IOException("1"))
              .fail(new IOException("2"))
              .fail(new IOException("3"))
              .respond(200, Map.of(), "{}");

      newLoop(3).execute(call, "GET", URL);

      assertThat(sleeper.sleeps)
          .containsExactly(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400));
    }
  }

  @Nested
  class TransportFailures {

    @Test
    void an_io_failure_is_a_network_error_and_is_retried() {
      IOException cause = new IOException("connection reset");
      FakeAttempt call = new FakeAttempt().fail(cause).fail(cause).fail(cause);

      PoliPageException e = thrown(() -> newLoop(2).execute(call, "GET", URL));

      assertThat(e).isInstanceOf(PoliPageNetworkException.class);
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.NETWORK_ERROR);
      assertThat(e.getMessage()).isEqualTo("Network request failed");
      assertThat(e.getCause()).isSameAs(cause);
      assertThat(e.statusCode()).isZero();
      assertThat(e.requestId()).isNull();
      assertThat(call.sent).isEqualTo(3);
      assertThat(retries).extracting(RetryEvent::reason).containsOnly("network_error");
      assertThat(retries).extracting(RetryEvent::statusCode).containsOnlyNulls();
      assertThat(errors).containsExactly(e);
    }

    @Test
    void an_http_timeout_is_a_timeout_with_the_configured_bound() {
      FakeAttempt call =
          new FakeAttempt()
              .fail(new HttpTimeoutException("request timed out"))
              .respond(200, Map.of(), "{}");

      newLoop(1, Duration.ofMillis(1234)).execute(call, "GET", URL);

      assertThat(retries)
          .singleElement()
          .satisfies(e -> assertThat(e.reason()).isEqualTo("timeout"));

      FakeAttempt exhausted = new FakeAttempt().fail(new HttpTimeoutException("request timed out"));
      PoliPageException e =
          thrown(() -> newLoop(0, Duration.ofMillis(1234)).execute(exhausted, "GET", URL));
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.TIMEOUT);
      assertThat(e.getMessage()).isEqualTo("Request timed out after 1234ms");
      assertThat(e.getCause()).isInstanceOf(HttpTimeoutException.class);
    }

    @Test
    void an_attempt_that_never_settles_is_cut_at_the_timeout() {
      FakeAttempt call = new FakeAttempt().hang();
      long started = System.nanoTime();

      PoliPageException e =
          thrown(() -> newLoop(0, Duration.ofMillis(100)).execute(call, "GET", URL));

      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.TIMEOUT);
      assertThat(e.getMessage()).isEqualTo("Request timed out after 100ms");
      assertThat(elapsedMs).isBetween(90L, 2_000L);
      assertThat(call.lastExchange().isCancelled()).isTrue();
    }

    @Test
    void a_2xx_whose_body_breaks_off_is_a_network_error_and_fires_no_on_response() {
      IOException cut = new IOException("connection closed mid-body");
      FakeAttempt call =
          new FakeAttempt().bodyBreaks(200, Map.of(), cut).respond(200, Map.of(), "{}");

      newLoop(1).execute(call, "GET", URL);

      assertThat(retries)
          .singleElement()
          .satisfies(e -> assertThat(e.reason()).isEqualTo("network_error"));
      assertThat(responses).hasSize(1);
    }

    @Test
    void an_error_answer_whose_body_breaks_off_is_a_malformed_body_retried_by_its_status() {
      IOException cut = new IOException("connection closed mid-body");
      FakeAttempt call =
          new FakeAttempt()
              .bodyBreaks(503, Map.of("x-request-id", "req_cut", "Retry-After", "0"), cut)
              .bodyBreaks(503, Map.of("x-request-id", "req_cut_2"), cut);

      PoliPageException e = thrown(() -> newLoop(1).execute(call, "GET", URL));

      assertThat(e.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(e.getMessage()).isEqualTo("HTTP 503");
      assertThat(e.statusCode()).isEqualTo(503);
      assertThat(e.requestId()).isEqualTo("req_cut_2");
      assertThat(e.getCause()).isSameAs(cut);
      assertThat(sleeper.sleeps).containsExactly(Duration.ZERO);
    }

    @Test
    void a_4xx_whose_body_breaks_off_is_not_retried() {
      FakeAttempt call =
          new FakeAttempt()
              .bodyBreaks(429, Map.of(), new IOException("cut"))
              .respond(200, Map.of(), "{}");
      PoliPageException rateLimited = thrown(() -> newLoop(0).execute(call, "GET", URL));
      assertThat(rateLimited).isInstanceOf(PoliPageRateLimitException.class);

      FakeAttempt badRequest = new FakeAttempt().bodyBreaks(400, Map.of(), new IOException("cut"));
      PoliPageException e = thrown(() -> newLoop(2).execute(badRequest, "GET", URL));
      assertThat(e.code()).isEqualTo(PoliPageErrorCode.INTERNAL_ERROR);
      assertThat(e.statusCode()).isEqualTo(400);
      assertThat(badRequest.sent).isEqualTo(1);
    }

    @Test
    void a_failure_that_is_not_io_is_rethrown_as_is() {
      IllegalStateException bug = new IllegalStateException("bug");
      FakeAttempt call = new FakeAttempt().fail(bug);

      assertThatThrownBy(() -> newLoop(2).execute(call, "GET", URL)).isSameAs(bug);
      assertThat(call.sent).isEqualTo(1);
    }
  }

  @Nested
  class Interruption {

    @Test
    void an_interrupt_during_an_attempt_aborts_without_retry() {
      FakeAttempt call = new FakeAttempt().hang();
      Thread.currentThread().interrupt();
      try {
        PoliPageException e = thrown(() -> newLoop(2).execute(call, "GET", URL));

        assertThat(e.code()).isEqualTo(PoliPageErrorCode.ABORTED);
        assertThat(e.getMessage()).isEqualTo("Request was aborted");
        assertThat(e.isRetryable()).isFalse();
        assertThat(e.getCause()).isInstanceOf(InterruptedException.class);
        assertThat(call.sent).isEqualTo(1);
        assertThat(call.lastExchange().isCancelled()).isTrue();
        assertThat(errors).containsExactly(e);
      } finally {
        assertThat(Thread.interrupted()).isTrue();
      }
    }

    @Test
    void an_interrupt_during_the_wait_aborts() {
      FakeAttempt call =
          new FakeAttempt().respond(503, Map.of(), apiError("X")).respond(200, Map.of(), "{}");
      sleeper.throwOnNextSleep = true;
      try {
        PoliPageException e = thrown(() -> newLoop(2).execute(call, "GET", URL));

        assertThat(e.code()).isEqualTo(PoliPageErrorCode.ABORTED);
        assertThat(e.getMessage()).isEqualTo("Request was aborted");
        assertThat(call.sent).isEqualTo(1);
      } finally {
        assertThat(Thread.interrupted()).isTrue();
      }
    }
  }

  @Test
  void hooks_that_throw_never_break_the_request() {
    RetryLoop loop =
        new RetryLoop(
            2,
            BASE_DELAY,
            TIMEOUT,
            new Backoff(() -> 1.0),
            sleeper,
            new ObjectMapper(),
            new Hooks(
                e -> {
                  throw new IllegalStateException("onRequest");
                },
                e -> {
                  throw new IllegalStateException("onResponse");
                },
                e -> {
                  throw new IllegalStateException("onRetry");
                },
                e -> {
                  throw new IllegalStateException("onError");
                }));
    FakeAttempt ok =
        new FakeAttempt().respond(503, Map.of(), apiError("X")).respond(200, Map.of(), "{}");
    assertThat(loop.execute(ok, "GET", URL).statusCode()).isEqualTo(200);

    FakeAttempt failing = new FakeAttempt().respond(404, Map.of(), apiError("NOT_FOUND"));
    assertThat(thrown(() -> loop.execute(failing, "GET", URL)).code()).isEqualTo("NOT_FOUND");
  }

  @Nested
  class Async {

    @Test
    void retries_then_completes_with_the_2xx_answer() {
      FakeAttempt call =
          new FakeAttempt().fail(new IOException("reset")).respond(200, Map.of(), "{}");

      HttpResponse<byte[]> resp = newLoop(2).executeAsync(call, "GET", URL).join();

      assertThat(resp.statusCode()).isEqualTo(200);
      assertThat(retries).extracting(RetryEvent::attempt).containsExactly(2);
      assertThat(requests).extracting(RequestEvent::attempt).containsExactly(1, 2);
    }

    @Test
    void completes_with_the_last_error_wrapped_once() {
      FakeAttempt call = new FakeAttempt().respond(400, Map.of(), apiError("VALIDATION_ERROR"));

      CompletableFuture<HttpResponse<byte[]>> future = newLoop(2).executeAsync(call, "POST", URL);

      assertThatThrownBy(future::join)
          .isInstanceOf(CompletionException.class)
          .cause()
          .isInstanceOf(PoliPageException.class)
          .satisfies(t -> assertThat(((PoliPageException) t).code()).isEqualTo("VALIDATION_ERROR"));
      assertThat(errors).hasSize(1);
    }

    @Test
    void an_attempt_that_never_settles_is_cut_at_the_timeout() {
      FakeAttempt call = new FakeAttempt().hang();

      CompletableFuture<HttpResponse<byte[]>> future =
          newLoop(0, Duration.ofMillis(100)).executeAsync(call, "GET", URL);

      assertThatThrownBy(() -> future.get(3, TimeUnit.SECONDS))
          .cause()
          .satisfies(
              t ->
                  assertThat(((PoliPageException) t).getMessage())
                      .isEqualTo("Request timed out after 100ms"));
      assertThat(call.lastExchange().isCancelled()).isTrue();
    }

    @Test
    void cancelling_during_an_attempt_cancels_the_exchange_and_sends_nothing_more() {
      FakeAttempt call = new FakeAttempt().hang();
      CompletableFuture<HttpResponse<byte[]>> future = newLoop(2).executeAsync(call, "GET", URL);

      future.cancel(true);

      assertThat(call.lastExchange().isCancelled()).isTrue();
      assertThatThrownBy(future::join).isInstanceOf(CancellationException.class);
      assertThat(call.sent).isEqualTo(1);
    }

    @Test
    void cancelling_during_the_wait_stops_the_next_attempt() throws Exception {
      FakeAttempt call =
          new FakeAttempt()
              .respond(503, Map.of("Retry-After", "1"), apiError("PUBLISH_IN_PROGRESS"))
              .respond(200, Map.of(), "{}");
      CompletableFuture<HttpResponse<byte[]>> future = newLoop(2).executeAsync(call, "POST", URL);
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (retries.isEmpty() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
      }

      future.cancel(true);
      TimeUnit.MILLISECONDS.sleep(1_300);

      assertThat(call.sent).isEqualTo(1);
      assertThat(retries)
          .singleElement()
          .satisfies(e -> assertThat(e.delay()).isEqualTo(Duration.ofSeconds(1)));
    }

    @Test
    void the_wait_before_a_retry_is_never_shorter_than_the_delay() {
      FakeAttempt call =
          new FakeAttempt()
              .fail(new IOException("reset"))
              .fail(new IOException("reset"))
              .respond(200, Map.of(), "{}");
      RetryLoop loop =
          new RetryLoop(
              2,
              Duration.ofMillis(37),
              TIMEOUT,
              new Backoff(() -> 1.0),
              sleeper,
              new ObjectMapper(),
              Hooks.NONE);

      loop.executeAsync(call, "GET", URL).join();

      // Retry 1 waits 37 ms, retry 2 waits 74 ms, counted from the failed attempt.
      assertThat(call.sentAt.get(1) - call.settledAt.get(0))
          .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(37));
      assertThat(call.sentAt.get(2) - call.settledAt.get(1))
          .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(74));
    }
  }

  @Test
  void the_blocking_sleeper_never_wakes_before_the_delay() throws InterruptedException {
    for (long nanos : new long[] {1_300_000L, 2_700_000L, 7_100_000L, 15_900_000L}) {
      long started = System.nanoTime();
      Sleeper.THREAD.sleep(Duration.ofNanos(nanos));
      assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(nanos);
    }
  }

  // -- fakes -----------------------------------------------------------

  /** Answers each attempt with the next scripted step. */
  private static final class FakeAttempt implements RetryLoop.Attempt {

    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<CompletableFuture<HttpResponse<byte[]>>> exchanges =
        Collections.synchronizedList(new ArrayList<>());
    final List<Long> sentAt = Collections.synchronizedList(new ArrayList<>());
    final List<Long> settledAt = Collections.synchronizedList(new ArrayList<>());
    volatile int sent;

    private interface Step {
      CompletableFuture<HttpResponse<byte[]>> run(ResponseCapture<byte[]> capture);
    }

    FakeAttempt respond(int status, Map<String, String> headers, String body) {
      steps.add(
          capture ->
              CompletableFuture.completedFuture(
                  new FakeResponse(status, headers, body.getBytes(StandardCharsets.UTF_8))));
      return this;
    }

    FakeAttempt fail(Throwable error) {
      steps.add(capture -> CompletableFuture.failedFuture(error));
      return this;
    }

    FakeAttempt bodyBreaks(int status, Map<String, String> headers, IOException error) {
      steps.add(
          capture -> {
            capture.apply(new FakeInfo(status, headersOf(headers)));
            return CompletableFuture.failedFuture(error);
          });
      return this;
    }

    FakeAttempt hang() {
      steps.add(capture -> new CompletableFuture<>());
      return this;
    }

    CompletableFuture<HttpResponse<byte[]>> lastExchange() {
      return exchanges.get(exchanges.size() - 1);
    }

    @Override
    public CompletableFuture<HttpResponse<byte[]>> send(ResponseCapture<byte[]> capture) {
      sent++;
      sentAt.add(System.nanoTime());
      Step step = steps.poll();
      if (step == null) {
        throw new AssertionError("more attempts than scripted steps");
      }
      CompletableFuture<HttpResponse<byte[]>> exchange = step.run(capture);
      settledAt.add(0L);
      int index = settledAt.size() - 1;
      exchange.whenComplete((r, e) -> settledAt.set(index, System.nanoTime()));
      exchanges.add(exchange);
      return exchange;
    }
  }

  private static final class RecordingSleeper implements Sleeper {
    final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    volatile boolean throwOnNextSleep = false;

    @Override
    public void sleep(Duration duration) throws InterruptedException {
      if (throwOnNextSleep) {
        throwOnNextSleep = false;
        throw new InterruptedException("test interrupt during sleep");
      }
      sleeps.add(duration);
    }
  }

  private static HttpHeaders headersOf(Map<String, String> headers) {
    Map<String, List<String>> map = new HashMap<>();
    headers.forEach((k, v) -> map.put(k, List.of(v)));
    return HttpHeaders.of(map, (k, v) -> true);
  }

  private record FakeInfo(int statusCode, HttpHeaders headers) implements ResponseInfo {
    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }

  /** Minimal {@link HttpResponse} stand-in. */
  private static final class FakeResponse implements HttpResponse<byte[]> {
    private final int status;
    private final HttpHeaders headers;
    private final byte[] body;

    FakeResponse(int status, Map<String, String> headers, byte[] body) {
      this.status = status;
      this.headers = headersOf(headers);
      this.body = body;
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
      return body;
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
      return URL;
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
