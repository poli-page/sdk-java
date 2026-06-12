package page.poli.sdk.internal;

import java.net.URI;

/**
 * URL composition helper.
 *
 * <p>Why not {@link URI#resolve(String)}: RFC 3986 says that resolving an absolute path (one
 * starting with {@code /}) against a base URI replaces the base's path entirely. The SDK's endpoint
 * paths are all absolute ({@code "/v1/render"}, …), so {@code resolve} drops any path prefix on the
 * configured base URL (e.g. {@code https://gw.example/tenant-a} loses the {@code /tenant-a}
 * segment). String concatenation — matching the Node reference SDK — preserves the prefix.
 */
public final class Urls {

  private Urls() {}

  /**
   * Append a path to a base URI as plain strings, collapsing the boundary so the result has exactly
   * one slash between the two regardless of trailing/leading slashes on either side.
   *
   * @param base absolute base URI (scheme + authority, optionally a path prefix)
   * @param path path to append (typically starts with {@code /})
   * @return the joined URI
   */
  public static URI join(URI base, String path) {
    String b = base.toString();
    if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
    if (path.isEmpty()) return URI.create(b);
    String p = path.startsWith("/") ? path : "/" + path;
    return URI.create(b + p);
  }
}
