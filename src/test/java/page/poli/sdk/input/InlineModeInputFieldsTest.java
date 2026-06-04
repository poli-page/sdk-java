package page.poli.sdk.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import page.poli.sdk.model.Orientation;
import page.poli.sdk.model.PageFormat;

class InlineModeInputFieldsTest {

  @Test
  void builder_accepts_format_orientation_locale() {
    InlineModeInput input =
        InlineModeInput.builder()
            .template("<h1>{{title}}</h1>")
            .data(Map.of("title", "Hi"))
            .format(PageFormat.A5)
            .orientation(Orientation.PORTRAIT)
            .locale("ja-JP")
            .build();

    assertThat(input.format()).isEqualTo(PageFormat.A5);
    assertThat(input.orientation()).isEqualTo(Orientation.PORTRAIT);
    assertThat(input.locale()).isEqualTo("ja-JP");
  }

  @Test
  void serializes_new_fields_when_set() throws Exception {
    InlineModeInput input =
        InlineModeInput.builder()
            .template("<p/>")
            .data(Map.of())
            .format(PageFormat.Tabloid)
            .build();

    String json = JsonMapper.builder().build().writeValueAsString(input);
    assertThat(json).contains("\"format\":\"Tabloid\"");
  }

  @Test
  void idempotency_key_is_not_serialized() throws Exception {
    InlineModeInput input =
        InlineModeInput.builder()
            .template("<p/>")
            .data(Map.of())
            .idempotencyKey("inline-key-999")
            .build();

    assertThat(input.idempotencyKey()).isEqualTo("inline-key-999");
    String json = JsonMapper.builder().build().writeValueAsString(input);
    assertThat(json).doesNotContain("idempotencyKey").doesNotContain("inline-key-999");
  }
}
