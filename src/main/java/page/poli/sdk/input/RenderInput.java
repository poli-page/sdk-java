package page.poli.sdk.input;

/**
 * Sealed supertype accepted by render methods that work with either rendering mode.
 *
 * <ul>
 *   <li>{@link ProjectModeInput} addresses a stored project / template by slug, optionally pinned
 *       to a published version.
 *   <li>{@link InlineModeInput} carries the raw HTML inline — no project resolution.
 * </ul>
 *
 * <p>Methods that <em>require</em> project mode ({@code render().pdf}, {@code render().pdfStream},
 * {@code render().document}) take {@link ProjectModeInput} directly — the compiler rejects passing
 * an {@link InlineModeInput}. {@code render().preview} is the only method that takes the sealed
 * {@code RenderInput}, satisfied by both subtypes.
 *
 * <p>Pattern-match exhaustively to handle both modes:
 *
 * <pre>{@code
 * String label = switch (input) {
 *     case ProjectModeInput p -> p.project() + "/" + p.template();
 *     case InlineModeInput  i -> "<inline " + i.template().length() + " bytes>";
 * };
 * }</pre>
 */
public sealed interface RenderInput permits ProjectModeInput, InlineModeInput {}
