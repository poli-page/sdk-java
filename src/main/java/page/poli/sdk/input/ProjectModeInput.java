package page.poli.sdk.input;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Render input that addresses a stored project / template by slug, optionally pinned to a published
 * version.
 *
 * <p>Construct via {@link #builder()}:
 *
 * <pre>{@code
 * ProjectModeInput input = ProjectModeInput.builder()
 *     .project("billing")
 *     .template("invoice")
 *     .version("1.0.0")
 *     .data(Map.of("invoiceNumber", "INV-001"))
 *     .metadata(Map.of("customerId", "cust_123"))
 *     .build();
 * }</pre>
 *
 * <p>{@code project}, {@code template}, and {@code data} are required. {@code version} is omitted
 * to render the draft. {@code metadata} is an optional caller-supplied bag of primitives echoed by
 * preview and document responses.
 *
 * @param project the project slug — required, non-blank
 * @param template the template slug — required, non-blank
 * @param version the published version to render; {@code null} renders the draft
 * @param data template data; required, may be empty but not {@code null}
 * @param metadata caller-supplied metadata echoed by preview / document responses; {@code null} to
 *     omit
 */
@JsonInclude(Include.NON_NULL)
public record ProjectModeInput(
    String project,
    String template,
    @Nullable String version,
    Map<String, Object> data,
    @Nullable Map<String, Object> metadata)
    implements RenderInput {

  /** Compact-constructor invariants — match the {@link Builder}'s validation. */
  public ProjectModeInput {
    Objects.requireNonNull(project, "project");
    Objects.requireNonNull(template, "template");
    Objects.requireNonNull(data, "data");
    if (project.isBlank()) {
      throw new IllegalArgumentException("project must not be blank");
    }
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

  /** Fluent builder for {@link ProjectModeInput}. */
  public static final class Builder {

    private @Nullable String project;
    private @Nullable String template;
    private @Nullable String version;
    private @Nullable Map<String, Object> data;
    private @Nullable Map<String, Object> metadata;

    private Builder() {}

    /**
     * Sets the project slug. Required.
     *
     * @param project non-null project slug
     * @return this builder
     */
    public Builder project(String project) {
      this.project = Objects.requireNonNull(project, "project");
      return this;
    }

    /**
     * Sets the template slug. Required.
     *
     * @param template non-null template slug
     * @return this builder
     */
    public Builder template(String template) {
      this.template = Objects.requireNonNull(template, "template");
      return this;
    }

    /**
     * Sets the published version slug. Optional — when omitted, the draft is rendered.
     *
     * @param version published version slug, or {@code null} to render the draft
     * @return this builder
     */
    public Builder version(@Nullable String version) {
      this.version = version;
      return this;
    }

    /**
     * Sets the template data. Required, may be empty.
     *
     * <p>The map is forwarded verbatim to the API as JSON. Use any JSON-compatible value type;
     * nested maps and lists are fine.
     *
     * @param data non-null template data map
     * @return this builder
     */
    public Builder data(Map<String, Object> data) {
      this.data = Map.copyOf(Objects.requireNonNull(data, "data"));
      return this;
    }

    /**
     * Sets caller-supplied metadata echoed on preview / document responses. Optional.
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
     * @throws NullPointerException if {@code project}, {@code template}, or {@code data} was never
     *     set
     */
    public ProjectModeInput build() {
      return new ProjectModeInput(
          Objects.requireNonNull(project, "project is required"),
          Objects.requireNonNull(template, "template is required"),
          version,
          Objects.requireNonNull(data, "data is required"),
          metadata);
    }
  }
}
