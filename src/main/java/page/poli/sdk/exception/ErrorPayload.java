package page.poli.sdk.exception;

import org.jspecify.annotations.Nullable;

/**
 * Canonical wire payload produced by {@link PoliPageException#toPayload()} for
 * framework integrations: {@code {code, message, status, requestId}}.
 *
 * <p>{@code status} surfaces the API HTTP status for status-bearing exceptions,
 * {@code 503} for network failures, {@code 504} for timeouts, or {@code null}
 * when no useful status applies (e.g. constructor validation). Integrations
 * write the fields verbatim to the HTTP body (flat JSON) or extract them into
 * RFC 7807 ProblemDetails extensions.
 *
 * @param code      the wire-level error code from the API
 * @param message   the human-readable error message
 * @param status    the HTTP status to surface, or {@code null}
 * @param requestId the server-assigned request identifier, or {@code null}
 */
public record ErrorPayload(
    String code, String message, @Nullable Integer status, @Nullable String requestId) {}
