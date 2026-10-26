package page.poli.sdk.input;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import page.poli.sdk.model.Orientation;
import page.poli.sdk.model.PageFormat;

/**
 * Render input: addresses a stored project / template by slug. The API renders the project's copy
 * published in the API key's environment.
 *
 * <p>Construct via {@link #builder()}:
 *
 * <pre>{@code
 * RenderInput input = RenderInput.builder()
 *     .project("billing")
 *     .template("invoice")
 *     .data(Map.of("invoiceNumber", "INV-001"))
 *     .metadata(Map.of("customerId", "cust_123"))
 *     .build();
 * }</pre>
 *
 * <p>The request body holds these seven fields only: {@code project}, {@code template}, and {@code
 * data} (required), {@code metadata}, {@code format}, {@code orientation}, and {@code locale}
 * (optional, omitted when unset). {@code metadata} is an optional caller-supplied bag of primitives
 * echoed by document responses.
 *
 * @param project the project slug — required, non-blank
 * @param template the template slug — required, non-blank
 * @param data template data; required, may be empty but not {@code null}
 * @param metadata caller-supplied metadata echoed by document responses; {@code null} to omit
 * @param format optional page format override; {@code null} uses the template default
 * @param orientation optional page orientation override; {@code null} uses the template default
 * @param locale optional BCP 47 locale (e.g. {@code "en-US"}); {@code null} uses the template
 *     default
 * @param idempotencyKey caller-supplied idempotency key; when set, overrides the SDK-generated UUID
 *     sent in the {@code Idempotency-Key} request header — never serialised to the wire body
 */
@JsonInclude(Include.NON_NULL)
public record RenderInput(
    String project,
    String template,
    Map<String, Object> data,
    @Nullable Map<String, Object> metadata,
    @Nullable PageFormat format,
    @Nullable Orientation orientation,
    @Nullable String locale,
    @JsonIgnore @Nullable String idempotencyKey) {

  /** Compact-constructor invariants — match the {@link Builder}'s validation. */
  public RenderInput {
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

  /** Fluent builder for {@link RenderInput}. */
  public static final class Builder {

    private @Nullable String project;
    private @Nullable String template;
    private @Nullable Map<String, Object> data;
    private @Nullable Map<String, Object> metadata;
    private @Nullable PageFormat format;
    private @Nullable Orientation orientation;
    private @Nullable String locale;
    private @Nullable String idempotencyKey;

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
     * Sets caller-supplied metadata echoed on document responses. Optional.
     *
     * @param metadata metadata bag, or {@code null} to clear
     * @return this builder
     */
    public Builder metadata(@Nullable Map<String, Object> metadata) {
      this.metadata = metadata == null ? null : Map.copyOf(metadata);
      return this;
    }

    /**
     * Sets the page format override. Optional.
     *
     * @param format page format, or {@code null} to use the template default
     * @return this builder
     */
    public Builder format(@Nullable PageFormat format) {
      this.format = format;
      return this;
    }

    /**
     * Sets the page orientation override. Optional.
     *
     * @param orientation page orientation, or {@code null} to use the template default
     * @return this builder
     */
    public Builder orientation(@Nullable Orientation orientation) {
      this.orientation = orientation;
      return this;
    }

    /**
     * Sets the BCP 47 locale (e.g. {@code "en-US"}). Optional.
     *
     * @param locale locale string, or {@code null} to use the template default
     * @return this builder
     */
    public Builder locale(@Nullable String locale) {
      this.locale = locale;
      return this;
    }

    /**
     * Sets a caller-supplied idempotency key. Optional — when omitted, the SDK generates a UUID.
     * The key is sent in the {@code Idempotency-Key} request header and is never serialised to the
     * wire body.
     *
     * @param idempotencyKey the idempotency key, or {@code null} to let the SDK generate one
     * @return this builder
     */
    public Builder idempotencyKey(@Nullable String idempotencyKey) {
      this.idempotencyKey = idempotencyKey;
      return this;
    }

    /**
     * Validates and produces the input record.
     *
     * @return an immutable input record
     * @throws NullPointerException if {@code project}, {@code template}, or {@code data} was never
     *     set
     */
    public RenderInput build() {
      return new RenderInput(
          Objects.requireNonNull(project, "project is required"),
          Objects.requireNonNull(template, "template is required"),
          Objects.requireNonNull(data, "data is required"),
          metadata,
          format,
          orientation,
          locale,
          idempotencyKey);
    }
  }
}
