package page.poli.sdk.internal;

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.RequestEvent;
import page.poli.sdk.ResponseEvent;
import page.poli.sdk.RetryEvent;

/**
 * The client's observability hooks. Each one is optional, and a hook that throws never breaks the
 * request: its error is swallowed.
 *
 * @param onRequest before each attempt
 * @param onResponse after each 2xx answer, once its body is read
 * @param onRetry before each wait between attempts
 * @param onError once, with the error a call is about to throw
 */
public record Hooks(
    @Nullable Consumer<RequestEvent> onRequest,
    @Nullable Consumer<ResponseEvent> onResponse,
    @Nullable Consumer<RetryEvent> onRetry,
    @Nullable Consumer<Throwable> onError) {

  /** No hook at all. */
  public static final Hooks NONE = new Hooks(null, null, null, null);

  void request(RequestEvent event) {
    fire(onRequest, event);
  }

  void response(ResponseEvent event) {
    fire(onResponse, event);
  }

  void retry(RetryEvent event) {
    fire(onRetry, event);
  }

  /**
   * Fire {@code onError}.
   *
   * @param error the error a call is about to end with
   */
  public void error(Throwable error) {
    fire(onError, error);
  }

  private static <T> void fire(@Nullable Consumer<T> hook, T event) {
    if (hook == null) {
      return;
    }
    try {
      hook.accept(event);
    } catch (Throwable ignored) {
      // A faulty hook never breaks the request.
    }
  }
}
