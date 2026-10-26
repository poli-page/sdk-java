package page.poli.sdk.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThumbnailOptionsTest {

  @Test
  void builder_with_no_option_leaves_every_component_unset() {
    ThumbnailOptions o = ThumbnailOptions.builder().build();
    assertThat(o.width()).isNull();
    assertThat(o.format()).isNull();
    assertThat(o.quality()).isNull();
    assertThat(o.pages()).isNull();
    assertThat(o.idempotencyKey()).isNull();
  }

  @Test
  void unset_options_are_not_serialized() throws Exception {
    String json =
        JsonMapper.builder().build().writeValueAsString(ThumbnailOptions.builder().build());
    assertThat(json).isEqualTo("{}");
  }

  @Test
  void only_the_set_options_are_serialized() throws Exception {
    String json =
        JsonMapper.builder()
            .build()
            .writeValueAsString(ThumbnailOptions.builder().format(ThumbnailFormat.WEBP).build());
    assertThat(json).isEqualTo("{\"format\":\"webp\"}");
  }

  @Test
  void no_client_side_bounds_check() {
    // The API owns the bounds (width 50–2000, quality 1–100 for jpeg only) and answers
    // 400 VALIDATION_ERROR; the SDK forwards whatever the caller sets.
    ThumbnailOptions o = new ThumbnailOptions(0, ThumbnailFormat.PNG, 500, List.of(0, -1), null);
    assertThat(o.width()).isZero();
    assertThat(o.quality()).isEqualTo(500);
    assertThat(o.pages()).containsExactly(0, -1);
  }

  @Test
  void quality_accepted_when_format_is_JPEG() {
    ThumbnailOptions o =
        ThumbnailOptions.builder().width(320).format(ThumbnailFormat.JPEG).quality(80).build();
    assertThat(o.quality()).isEqualTo(80);
  }

  @Test
  void pages_are_defensively_copied() {
    List<Integer> pages = new ArrayList<>(List.of(1, 2, 3));
    ThumbnailOptions o = ThumbnailOptions.builder().pages(pages).build();
    pages.add(4);
    assertThat(o.pages()).containsExactly(1, 2, 3);
  }

  @Test
  void thumbnail_format_serializes_to_lowercase() {
    assertThat(ThumbnailFormat.PNG.wireValue()).isEqualTo("png");
    assertThat(ThumbnailFormat.JPEG.wireValue()).isEqualTo("jpeg");
    assertThat(ThumbnailFormat.WEBP.wireValue()).isEqualTo("webp");
  }

  @Test
  void thumbnail_format_deserializes_from_lowercase() {
    assertThat(ThumbnailFormat.fromWire("png")).isEqualTo(ThumbnailFormat.PNG);
    assertThat(ThumbnailFormat.fromWire("jpeg")).isEqualTo(ThumbnailFormat.JPEG);
    assertThat(ThumbnailFormat.fromWire("webp")).isEqualTo(ThumbnailFormat.WEBP);
  }
}
