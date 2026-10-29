package page.poli.sdk.internal;

/**
 * SDK version embedded in the {@code User-Agent} header. Bumped manually with each release, in
 * lockstep with the {@code pom.xml} {@code <version>} element.
 */
public final class Version {

  /** Current SDK version. */
  public static final String VALUE = "0.10.0";

  private Version() {}
}
