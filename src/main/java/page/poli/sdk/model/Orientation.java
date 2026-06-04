package page.poli.sdk.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/**
 * Page orientation enum shared across every Poli Page SDK. Wire strings are lowercase ({@code
 * "portrait"}, {@code "landscape"}) — Java enum constants follow JVM uppercase idiom; Jackson
 * translates between the two via {@link JsonValue} and {@link JsonCreator}.
 */
public enum Orientation {
  PORTRAIT,
  LANDSCAPE;

  /**
   * @return the wire form of this orientation (lowercase)
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
   * @throws IllegalArgumentException if the value is not a recognized orientation
   */
  @JsonCreator
  public static Orientation fromWire(String value) {
    return Orientation.valueOf(value.toUpperCase(Locale.ROOT));
  }
}
