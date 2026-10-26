package page.poli.sdk.input;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import page.poli.sdk.model.Orientation;
import page.poli.sdk.model.PageFormat;

class RenderInputFieldsTest {

  @Test
  void record_components_are_the_seven_body_fields_plus_idempotency_key() {
    // The render body has exactly seven fields; idempotencyKey travels as a header. There is no
    // version and no inline mode.
    assertThat(RenderInput.class.isRecord()).isTrue();
    assertThat(Arrays.stream(RenderInput.class.getRecordComponents()).map(RecordComponent::getName))
        .containsExactly(
            "project",
            "template",
            "data",
            "metadata",
            "format",
            "orientation",
            "locale",
            "idempotencyKey");
  }

  @Test
  void builder_accepts_format_orientation_locale() {
    RenderInput input =
        RenderInput.builder()
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
  void serializes_exactly_the_seven_fields_when_all_set() throws Exception {
    RenderInput input =
        RenderInput.builder()
            .project("billing")
            .template("invoice")
            .data(Map.of("k", "v"))
            .metadata(Map.of("customerId", "cust_1"))
            .format(PageFormat.A4)
            .orientation(Orientation.PORTRAIT)
            .locale("en-US")
            .idempotencyKey("key-1")
            .build();

    JsonNode json = JsonMapper.builder().build().valueToTree(input);

    List<String> keys = new ArrayList<>();
    json.fieldNames().forEachRemaining(keys::add);
    assertThat(keys)
        .containsExactlyInAnyOrder(
            "project", "template", "data", "metadata", "format", "orientation", "locale");
    assertThat(json.get("format").asText()).isEqualTo("A4");
    assertThat(json.get("orientation").asText()).isEqualTo("portrait");
    assertThat(json.get("locale").asText()).isEqualTo("en-US");
  }

  @Test
  void omits_unset_optional_fields() throws Exception {
    RenderInput input = RenderInput.builder().project("p").template("t").data(Map.of()).build();

    JsonNode json = JsonMapper.builder().build().valueToTree(input);

    List<String> keys = new ArrayList<>();
    json.fieldNames().forEachRemaining(keys::add);
    assertThat(keys).containsExactlyInAnyOrder("project", "template", "data");
  }

  @Test
  void idempotency_key_is_not_serialized() throws Exception {
    RenderInput input =
        RenderInput.builder()
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
