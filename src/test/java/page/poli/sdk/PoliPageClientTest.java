package page.poli.sdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PoliPageClientTest {

  private static final String TEST_KEY = "pp_test_xxx";

  @Nested
  class BuilderValidation {

    @Test
    void build_throws_when_apiKey_missing() {
      assertThatThrownBy(() -> PoliPageClient.builder().build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("apiKey");
    }

    @Test
    void build_throws_when_apiKey_blank() {
      assertThatThrownBy(() -> PoliPageClient.builder().apiKey("   ").build())
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("apiKey");
    }

    @Test
    void apiKey_throws_NullPointerException_when_null_passed() {
      // Production code rejects null even though @NullMarked makes this a contract violation —
      // we want a loud failure at runtime, not silent state corruption.
      assertThatThrownBy(() -> PoliPageClient.builder().apiKey(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("apiKey");
    }

    @Test
    void build_returns_client_when_apiKey_set() {
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).build();
      assertThat(client).isNotNull();
    }

    @Test
    void baseUrl_throws_when_not_absolute() {
      assertThatThrownBy(
              () -> PoliPageClient.builder().apiKey(TEST_KEY).baseUrl(URI.create("/relative")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("absolute");
    }

    @Test
    void baseUrl_throws_NullPointerException_when_null_passed() {
      assertThatThrownBy(() -> PoliPageClient.builder().apiKey(TEST_KEY).baseUrl(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessage("baseUrl");
    }

    @Test
    void maxRetries_throws_when_negative() {
      assertThatThrownBy(() -> PoliPageClient.builder().apiKey(TEST_KEY).maxRetries(-1))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maxRetries");
    }

    @Test
    void maxRetries_accepts_zero() {
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).maxRetries(0).build();
      assertThat(client.options().maxRetries()).isZero();
    }

    @Test
    void retryDelay_throws_when_zero() {
      assertThatThrownBy(() -> PoliPageClient.builder().apiKey(TEST_KEY).retryDelay(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("retryDelay");
    }

    @Test
    void retryDelay_throws_when_negative() {
      assertThatThrownBy(
              () -> PoliPageClient.builder().apiKey(TEST_KEY).retryDelay(Duration.ofMillis(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("retryDelay");
    }

    @Test
    void requestTimeout_throws_when_zero() {
      assertThatThrownBy(
              () -> PoliPageClient.builder().apiKey(TEST_KEY).requestTimeout(Duration.ZERO))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("requestTimeout");
    }

    @Test
    void requestTimeout_throws_when_negative() {
      assertThatThrownBy(
              () ->
                  PoliPageClient.builder().apiKey(TEST_KEY).requestTimeout(Duration.ofSeconds(-1)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("requestTimeout");
    }
  }

  @Nested
  class Defaults {

    @Test
    void default_baseUrl_is_api_poli_page() {
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).build();
      assertThat(client.options().baseUrl()).isEqualTo(URI.create("https://api.poli.page"));
    }

    @Test
    void default_maxRetries_is_2() {
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).build();
      assertThat(client.options().maxRetries()).isEqualTo(2);
    }

    @Test
    void default_retryDelay_is_500ms() {
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).build();
      assertThat(client.options().retryDelay()).isEqualTo(Duration.ofMillis(500));
    }

    @Test
    void default_requestTimeout_is_60s() {
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).build();
      assertThat(client.options().requestTimeout()).isEqualTo(Duration.ofSeconds(60));
    }
  }

  @Nested
  class Overrides {

    @Test
    void apiKey_is_preserved() {
      PoliPageClient client = PoliPageClient.builder().apiKey("pp_test_secret_value").build();
      assertThat(client.options().apiKey()).isEqualTo("pp_test_secret_value");
    }

    @Test
    void all_fields_can_be_overridden() {
      URI customBase = URI.create("https://api-develop.poli.page");
      PoliPageClient client =
          PoliPageClient.builder()
              .apiKey(TEST_KEY)
              .baseUrl(customBase)
              .maxRetries(5)
              .retryDelay(Duration.ofMillis(250))
              .requestTimeout(Duration.ofSeconds(30))
              .build();

      assertThat(client.options().baseUrl()).isEqualTo(customBase);
      assertThat(client.options().maxRetries()).isEqualTo(5);
      assertThat(client.options().retryDelay()).isEqualTo(Duration.ofMillis(250));
      assertThat(client.options().requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void builder_setters_are_chainable() {
      // Compile-time: each setter returns the Builder for fluent chaining.
      PoliPageClient.Builder b =
          PoliPageClient.builder()
              .apiKey(TEST_KEY)
              .baseUrl(URI.create("https://example.com"))
              .maxRetries(1)
              .retryDelay(Duration.ofMillis(100))
              .requestTimeout(Duration.ofSeconds(10));
      assertThat(b).isNotNull();
    }
  }

  @org.junit.jupiter.api.Nested
  class Hooks {

    @Test
    void onRetry_defaults_to_null() {
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).build();
      assertThat(client.options().onRetry()).isNull();
    }

    @Test
    void onError_defaults_to_null() {
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).build();
      assertThat(client.options().onError()).isNull();
    }

    @Test
    void onRetry_is_preserved() {
      java.util.function.Consumer<page.poli.sdk.RetryEvent> hook = e -> {};
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).onRetry(hook).build();
      assertThat(client.options().onRetry()).isSameAs(hook);
    }

    @Test
    void onError_is_preserved() {
      java.util.function.Consumer<Throwable> hook = err -> {};
      PoliPageClient client = PoliPageClient.builder().apiKey(TEST_KEY).onError(hook).build();
      assertThat(client.options().onError()).isSameAs(hook);
    }

    @Test
    void onRetry_null_clears_an_existing_hook() {
      java.util.function.Consumer<page.poli.sdk.RetryEvent> hook = e -> {};
      PoliPageClient client =
          PoliPageClient.builder().apiKey(TEST_KEY).onRetry(hook).onRetry(null).build();
      assertThat(client.options().onRetry()).isNull();
    }
  }
}
