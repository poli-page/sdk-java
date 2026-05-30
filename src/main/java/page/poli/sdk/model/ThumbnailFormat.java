package page.poli.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Output image format for {@code client.documents().thumbnails(...)}.
 *
 * <p>The wire representation is the lowercase form ({@code "png"}, {@code "jpeg"}) — Java enum
 * constants follow JVM uppercase idiom; Jackson translates between the two via {@link JsonValue}
 * and {@link JsonCreator}.
 */
public enum ThumbnailFormat {
  PNG,
  JPEG;

  /**
   * @return the wire form of this format (lowercase)
   */
  @JsonValue
  public String wireValue() {
    return name().toLowerCase(Locale.ROOT);
  }

  /**
   * Reverse-mapping used by Jackson on deserialization.
   *
   * @param value the lowercase wire form
   * @return the matching enum constant
   * @throws IllegalArgumentException if the value is not a recognized format
   */
  @JsonCreator
  public static ThumbnailFormat fromWire(String value) {
    return ThumbnailFormat.valueOf(value.toUpperCase(Locale.ROOT));
  }
}
