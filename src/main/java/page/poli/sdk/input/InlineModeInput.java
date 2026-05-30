package page.poli.sdk.input;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Render input that carries the raw HTML body inline — no project / template resolution against
 * stored versions. Accepted only by {@code render().preview} (other render methods require a stored
 * project for caching, versioning, and reproducibility).
 *
 * <p>Construct via {@link #builder()}:
 *
 * <pre>{@code
 * InlineModeInput input = InlineModeInput.builder()
 *     .template("<html><body>Hello {{name}}</body></html>")
 *     .data(Map.of("name", "World"))
 *     .build();
 * }</pre>
 *
 * @param template raw HTML body — required, non-blank
 * @param data template data; required, may be empty but not {@code null}
 * @param metadata caller-supplied metadata echoed by preview responses; {@code null} to omit
 */
@JsonInclude(Include.NON_NULL)
public record InlineModeInput(
    String template, Map<String, Object> data, @Nullable Map<String, Object> metadata)
    implements RenderInput {

  /** Compact-constructor invariants — match the {@link Builder}'s validation. */
  public InlineModeInput {
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(data, "data");
    if (template.isBlank()) {
      throw new IllegalArgumentException("template must not be blank");
    }
  }

  /**
   * Returns a fresh {@link Builder}.
   *
   * @return a new builder with no fields set
   */
  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for {@link InlineModeInput}. */
  public static final class Builder {

    private @Nullable String template;
    private @Nullable Map<String, Object> data;
    private @Nullable Map<String, Object> metadata;

    private Builder() {}

    /**
     * Sets the raw HTML body. Required.
     *
     * @param template non-null, non-blank HTML
     * @return this builder
     */
    public Builder template(String template) {
      this.template = Objects.requireNonNull(template, "template");
      return this;
    }

    /**
     * Sets the template data. Required, may be empty.
     *
     * @param data non-null template data map
     * @return this builder
     */
    public Builder data(Map<String, Object> data) {
      this.data = Map.copyOf(Objects.requireNonNull(data, "data"));
      return this;
    }

    /**
     * Sets caller-supplied metadata echoed on preview responses. Optional.
     *
     * @param metadata metadata bag, or {@code null} to clear
     * @return this builder
     */
    public Builder metadata(@Nullable Map<String, Object> metadata) {
      this.metadata = metadata == null ? null : Map.copyOf(metadata);
      return this;
    }

    /**
     * Validates and produces the input record.
     *
     * @return an immutable input record
     */
    public InlineModeInput build() {
      return new InlineModeInput(
          Objects.requireNonNull(template, "template is required"),
          Objects.requireNonNull(data, "data is required"),
          metadata);
    }
  }
}
