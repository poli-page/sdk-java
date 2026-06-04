package page.poli.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ThumbnailOptionsTest {

  @Test
  void builder_default_format_is_png() {
    ThumbnailOptions o = ThumbnailOptions.builder().width(320).build();
    assertThat(o.format()).isEqualTo(ThumbnailFormat.PNG);
    assertThat(o.quality()).isNull();
    assertThat(o.pages()).isNull();
  }

  @Test
  void builder_throws_when_width_missing() {
    assertThatThrownBy(() -> ThumbnailOptions.builder().build())
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("width");
  }

  @Test
  void compact_constructor_rejects_zero_width() {
    assertThatThrownBy(() -> new ThumbnailOptions(0, ThumbnailFormat.PNG, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("width");
  }

  @Test
  void compact_constructor_rejects_negative_width() {
    assertThatThrownBy(() -> new ThumbnailOptions(-1, ThumbnailFormat.PNG, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("width");
  }

  @Test
  void quality_rejected_when_format_is_PNG() {
    assertThatThrownBy(() -> new ThumbnailOptions(320, ThumbnailFormat.PNG, 80, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("JPEG");
  }

  @Test
  void quality_accepted_when_format_is_JPEG() {
    ThumbnailOptions o =
        ThumbnailOptions.builder().width(320).format(ThumbnailFormat.JPEG).quality(80).build();
    assertThat(o.quality()).isEqualTo(80);
  }

  @Test
  void quality_rejected_outside_1_to_100() {
    assertThatThrownBy(() -> new ThumbnailOptions(320, ThumbnailFormat.JPEG, 0, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
    assertThatThrownBy(() -> new ThumbnailOptions(320, ThumbnailFormat.JPEG, 101, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("100");
  }

  @Test
  void pages_rejected_when_any_value_is_below_1() {
    assertThatThrownBy(() -> ThumbnailOptions.builder().width(320).pages(List.of(1, 0, 3)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("1-based");
  }

  @Test
  void pages_accepts_valid_list() {
    ThumbnailOptions o = ThumbnailOptions.builder().width(320).pages(List.of(1, 2, 3)).build();
    assertThat(o.pages()).containsExactly(1, 2, 3);
  }

  @Test
  void thumbnail_format_serializes_to_lowercase() {
    assertThat(ThumbnailFormat.PNG.wireValue()).isEqualTo("png");
    assertThat(ThumbnailFormat.JPEG.wireValue()).isEqualTo("jpeg");
  }

  @Test
  void thumbnail_format_deserializes_from_lowercase() {
    assertThat(ThumbnailFormat.fromWire("png")).isEqualTo(ThumbnailFormat.PNG);
    assertThat(ThumbnailFormat.fromWire("jpeg")).isEqualTo(ThumbnailFormat.JPEG);
  }
}
