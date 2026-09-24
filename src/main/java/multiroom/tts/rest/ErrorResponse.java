package multiroom.tts.rest;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The exact shape {@code contracts/tts-rest-api.yaml} publishes for every non-2xx response.
 * Named {@code TtsErrorResponse} in the generated OpenAPI document — {@code multiroom-rest}
 * publishes its own, differently shaped RFC 7807 {@code ErrorResponse} under {@code /api/v2/**},
 * and springdoc's schema registry is keyed by simple class name across the whole shared
 * classpath, so two classes named {@code ErrorResponse} would collide in {@code /api-docs}.
 */
@Schema(name = "TtsErrorResponse")
public record ErrorResponse(String error, String message) {
}
