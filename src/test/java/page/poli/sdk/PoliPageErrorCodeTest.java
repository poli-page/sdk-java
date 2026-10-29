package page.poli.sdk;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoliPageErrorCodeTest {

  private static final List<String> API_CODES =
      List.of(
          // Added by the environments contract.
          "PUBLISH_IN_PROGRESS",
          "ENVIRONMENT_MISMATCH",
          "PROJECT_NOT_IN_ENVIRONMENT",
          "TEMPLATE_NOT_FOUND",
          "DOCUMENT_GONE",
          "ENVIRONMENT_PROTECTED",
          "ORGANIZATION_PAYMENT_GRACE",
          "EMPTY_DOCUMENT",
          "STORAGE_ACCESS_DENIED",
          "ORGANIZATION_MIGRATING",
          "PROJECT_NOT_FOUND",
          "TEMPLATE_ERROR",
          "THUMBNAILS_NOT_AVAILABLE",
          "RATE_LIMIT_EXCEEDED",
          "RENDER_BODY_TOO_LARGE",
          "DOCUMENT_TOO_LARGE",
          "RENDER_TIMEOUT",
          // Kept.
          "MISSING_API_KEY",
          "INVALID_API_KEY",
          "ORGANIZATION_CANCELLED",
          "ORGANIZATION_PURGED",
          "FORBIDDEN",
          "VALIDATION_ERROR",
          "MISSING_DATA",
          "MISSING_TEMPLATE_SLUG",
          "PROJECT_REQUIRED_FOR_DOCUMENT",
          "NOT_FOUND",
          "DOCUMENT_NOT_FOUND",
          "QUOTA_EXCEEDED",
          "OVERAGE_CAP_EXCEEDED",
          "INTERNAL_ERROR",
          // Added by SDK spec §8.4 (contract v1.2.0).
          "TEMPLATE_UNSAFE");

  private static final List<String> SDK_CODES =
      List.of("network_error", "timeout", "aborted", "invalid_options", "DOWNLOAD_FAILED");

  private static List<String> declaredValues() throws IllegalAccessException {
    List<String> values = new ArrayList<>();
    for (Field field : PoliPageErrorCode.class.getDeclaredFields()) {
      int mods = field.getModifiers();
      if (Modifier.isPublic(mods)
          && Modifier.isStatic(mods)
          && Modifier.isFinal(mods)
          && field.getType() == String.class) {
        values.add((String) field.get(null));
      }
    }
    return values;
  }

  @Test
  void known_codes_are_exactly_the_32_api_codes_plus_the_sdk_codes() throws Exception {
    assertThat(API_CODES).hasSize(32).doesNotHaveDuplicates();

    List<String> expected = new ArrayList<>(API_CODES);
    expected.addAll(SDK_CODES);

    assertThat(declaredValues()).containsExactlyInAnyOrderElementsOf(expected);
  }

  @Test
  void removed_codes_are_gone() throws Exception {
    assertThat(declaredValues())
        .doesNotContain(
            "VERSION_NOT_FOUND",
            "INVALID_VERSION_FORMAT",
            "VERSION_REQUIRED",
            "INVALID_VERSION_FOR_KEY_ENV",
            "MISSING_PROJECT_OR_TEMPLATE",
            "GONE",
            "PAYMENT_REQUIRED");
  }

  @Test
  void constant_names_match_their_api_values() throws Exception {
    for (String code : API_CODES) {
      assertThat(PoliPageErrorCode.class.getField(code).get(null)).isEqualTo(code);
    }
  }
}
