package page.poli.sdk.internal;

/**
 * SDK version embedded in the {@code User-Agent} header. Bumped manually as part of the release
 * checklist (see {@code sdk-java-plan.md} §12). Kept in lockstep with the {@code pom.xml}
 * {@code <version>} element.
 */
public final class Version {

  /** Current SDK version. */
  public static final String VALUE = "1.0.0-SNAPSHOT";

  private Version() {}
}
