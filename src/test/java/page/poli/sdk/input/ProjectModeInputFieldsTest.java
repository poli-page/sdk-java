package page.poli.sdk.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import page.poli.sdk.model.Orientation;
import page.poli.sdk.model.PageFormat;

class ProjectModeInputFieldsTest {

  @Test
  void builder_accepts_format_orientation_locale() {
    ProjectModeInput input =
        ProjectModeInput.builder()
            .project("billing")
            .template("invoice")
            .data(Map.of("k", "v"))
            .format(PageFormat.Letter)
            .orientation(Orientation.LANDSCAPE)
            .locale("fr-FR")
            .build();

    assertThat(input.format()).isEqualTo(PageFormat.Letter);
    assertThat(input.orientation()).isEqualTo(Orientation.LANDSCAPE);
    assertThat(input.locale()).isEqualTo("fr-FR");
  }

  @Test
  void serializes_new_fields_when_set() throws Exception {
    ProjectModeInput input =
        ProjectModeInput.builder()
            .project("billing")
            .template("invoice")
            .data(Map.of("k", "v"))
            .format(PageFormat.A4)
            .orientation(Orientation.PORTRAIT)
            .locale("en-US")
            .build();

    String json = JsonMapper.builder().build().writeValueAsString(input);

    assertThat(json).contains("\"format\":\"A4\"");
    assertThat(json).contains("\"orientation\":\"portrait\"");
    assertThat(json).contains("\"locale\":\"en-US\"");
  }

  @Test
  void omits_new_fields_when_unset() throws Exception {
    ProjectModeInput input =
        ProjectModeInput.builder().project("p").template("t").data(Map.of()).build();

    String json = JsonMapper.builder().build().writeValueAsString(input);

    assertThat(json)
        .doesNotContain("format")
        .doesNotContain("orientation")
        .doesNotContain("locale");
  }

  @Test
  void idempotency_key_is_not_serialized() throws Exception {
    ProjectModeInput input =
        ProjectModeInput.builder()
            .project("billing")
            .template("invoice")
            .data(Map.of())
            .idempotencyKey("my-key-123")
            .build();

    assertThat(input.idempotencyKey()).isEqualTo("my-key-123");
    String json = JsonMapper.builder().build().writeValueAsString(input);
    assertThat(json).doesNotContain("idempotencyKey").doesNotContain("my-key-123");
  }
}
