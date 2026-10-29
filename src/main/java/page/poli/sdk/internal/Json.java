package page.poli.sdk.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;

/** JSON serialization of request bodies. */
public final class Json {

  private Json() {}

  /**
   * Serialize a request body once per call; every attempt sends the same bytes.
   *
   * @param mapper the SDK's ObjectMapper
   * @param body the body
   * @return the JSON bytes
   * @throws UncheckedIOException if the body cannot be serialized
   */
  public static byte[] bytes(ObjectMapper mapper, Object body) {
    try {
      return mapper.writeValueAsBytes(body);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(
          "Failed to serialize request body of type " + body.getClass().getName(), e);
    }
  }
}
