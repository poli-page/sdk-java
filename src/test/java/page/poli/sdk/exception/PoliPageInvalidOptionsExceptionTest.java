package page.poli.sdk.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import page.poli.sdk.PoliPageClient;
import page.poli.sdk.PoliPageErrorCode;

class PoliPageInvalidOptionsExceptionTest {

  @Test
  void code_is_invalid_options() {
    PoliPageInvalidOptionsException e = new PoliPageInvalidOptionsException("apiKey is required");
    assertThat(e.code()).isEqualTo(PoliPageErrorCode.INVALID_OPTIONS);
    assertThat(e.code()).isEqualTo("invalid_options");
    assertThat(e.statusCode()).isZero();
    assertThat(e.requestId()).isNull();
  }

  @Test
  void is_subtype_of_PoliPageException() {
    PoliPageInvalidOptionsException e = new PoliPageInvalidOptionsException("nope");
    assertThat(e).isInstanceOf(PoliPageException.class);
  }

  @Test
  void builder_missing_apiKey_throws_PoliPageInvalidOptionsException() {
    assertThatThrownBy(() -> PoliPageClient.builder().build())
        .isInstanceOf(PoliPageInvalidOptionsException.class)
        .matches(t -> ((PoliPageException) t).code().equals("invalid_options"))
        .hasMessageContaining("apiKey");
  }

  @Test
  void builder_blank_apiKey_throws_PoliPageInvalidOptionsException() {
    assertThatThrownBy(() -> PoliPageClient.builder().apiKey("   ").build())
        .isInstanceOf(PoliPageInvalidOptionsException.class);
  }
}
