package page.poli.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class PageFormatTest {

  @Test
  void exposes_all_twelve_canonical_values() {
    assertThat(PageFormat.values())
        .extracting(Enum::name)
        .containsExactly(
            "A3",
            "A4",
            "A5",
            "A6",
            "B4",
            "B5",
            "Letter",
            "Legal",
            "Tabloid",
            "Executive",
            "Statement",
            "Folio");
  }

  @Test
  void serializes_to_pascal_case_wire_strings() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertThat(mapper.writeValueAsString(PageFormat.A4)).isEqualTo("\"A4\"");
    assertThat(mapper.writeValueAsString(PageFormat.Letter)).isEqualTo("\"Letter\"");
    assertThat(mapper.writeValueAsString(PageFormat.Folio)).isEqualTo("\"Folio\"");
  }

  @Test
  void deserializes_from_pascal_case_wire_strings() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertThat(mapper.readValue("\"A4\"", PageFormat.class)).isEqualTo(PageFormat.A4);
    assertThat(mapper.readValue("\"Letter\"", PageFormat.class)).isEqualTo(PageFormat.Letter);
  }
}
