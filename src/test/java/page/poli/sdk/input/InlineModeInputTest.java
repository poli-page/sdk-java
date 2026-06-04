package page.poli.sdk.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class InlineModeInputTest {

  @Test
  void builder_produces_record_with_required_fields() {
    InlineModeInput input =
        InlineModeInput.builder()
            .template("<html>{{name}}</html>")
            .data(Map.of("name", "World"))
            .build();

    assertThat(input.template()).isEqualTo("<html>{{name}}</html>");
    assertThat(input.data()).containsEntry("name", "World");
    assertThat(input.metadata()).isNull();
  }

  @Test
  void builder_supports_metadata() {
    InlineModeInput input =
        InlineModeInput.builder()
            .template("<p/>")
            .data(Map.of())
            .metadata(Map.of("trace", "abc"))
            .build();

    assertThat(input.metadata()).containsEntry("trace", "abc");
  }

  @Test
  void compact_constructor_rejects_null_template() {
    assertThatThrownBy(() -> new InlineModeInput(null, Map.of(), null, null, null, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("template");
  }

  @Test
  void compact_constructor_rejects_blank_template() {
    assertThatThrownBy(() -> new InlineModeInput("   ", Map.of(), null, null, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @Test
  void compact_constructor_rejects_null_data() {
    assertThatThrownBy(() -> new InlineModeInput("<p/>", null, null, null, null, null, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("data");
  }

  @Test
  void builder_throws_when_template_missing() {
    assertThatThrownBy(() -> InlineModeInput.builder().data(Map.of()).build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("template");
  }

  @Test
  void implements_RenderInput_sealed_interface() {
    RenderInput input = InlineModeInput.builder().template("<p/>").data(Map.of()).build();
    assertThat(input).isInstanceOf(InlineModeInput.class);
  }
}
