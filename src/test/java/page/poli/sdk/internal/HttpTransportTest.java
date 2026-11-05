package page.poli.sdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.Test;

/**
 * No request carries a JDK timeout: the JDK's timer reads the wall clock and truncates the time
 * left to whole milliseconds (JDK 17 to 25), so it can fire before the bound ({@code
 * R-WAIT-FULL}). The SDK's own monotonic timers bound the attempt and the download.
 */
class HttpTransportTest {

  private static final URI BASE = URI.create("http://localhost:1");

  @Test
  void an_api_request_carries_no_jdk_timeout() {
    CapturingClient http = new CapturingClient();
    HttpTransport transport = new HttpTransport(BASE, "pp_test", http);

    transport.send("GET", "/v1/documents/doc_1", null, null, BodyHandlers.ofByteArray());

    assertThat(http.sent.get().timeout()).isEmpty();
  }

  @Test
  void a_download_carries_no_jdk_timeout() {
    CapturingClient http = new CapturingClient();
    HttpTransport transport = new HttpTransport(BASE, "pp_test", http);

    transport.download(URI.create("http://localhost:1/file.pdf"), BodyHandlers.ofByteArray());

    assertThat(http.sent.get().timeout()).isEmpty();
  }

  /** Records the request; never answers. */
  private static final class CapturingClient extends HttpClient {
    final AtomicReference<HttpRequest> sent = new AtomicReference<>();

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, BodyHandler<T> handler) {
      sent.set(request);
      return new CompletableFuture<>();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, BodyHandler<T> handler, PushPromiseHandler<T> push) {
      return sendAsync(request, handler);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> handler)
        throws IOException {
      throw new IOException("not used");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
      return new SSLParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
      return Optional.empty();
    }
  }
}
