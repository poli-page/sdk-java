package page.poli.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OrientationTest {

  @Test
  void exposes_both_canonical_values() {
    assertThat(Orientation.values())
        .extracting(Enum::name)
        .containsExactly("PORTRAIT", "LANDSCAPE");
  }

  @Test
  void serializes_to_lowercase_wire_strings() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertThat(mapper.writeValueAsString(Orientation.PORTRAIT)).isEqualTo("\"portrait\"");
    assertThat(mapper.writeValueAsString(Orientation.LANDSCAPE)).isEqualTo("\"landscape\"");
  }

  @Test
  void deserializes_from_lowercase_wire_strings() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    assertThat(mapper.readValue("\"portrait\"", Orientation.class))
        .isEqualTo(Orientation.PORTRAIT);
    assertThat(mapper.readValue("\"landscape\"", Orientation.class))
        .isEqualTo(Orientation.LANDSCAPE);
  }
}
