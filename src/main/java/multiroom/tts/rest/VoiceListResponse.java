package multiroom.tts.rest;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import multiroom.tts.provider.cloud.google.CatalogueVoice;

import java.util.List;

/**
 * {@code GET /api/tts/providers/{name}/voices} response, matching {@code contracts/tts-rest-api.yaml}.
 * Named {@code TtsVoiceListResponse} in the generated OpenAPI document for the same reason as
 * {@link ErrorResponse}: springdoc keys schemas by simple class name across the shared classpath.
 *
 * @param providerName the provider the voices belong to
 * @param voices       the matching voices: for {@code google-cloud} sorted by language, engine,
 *                     then short name; for {@code google-gemini} sorted by name
 */
@Schema(name = "TtsVoiceListResponse")
public record VoiceListResponse(String providerName, List<VoiceDescriptor> voices) {

    static VoiceListResponse of(String providerName, List<CatalogueVoice> voices) {
        return new VoiceListResponse(providerName, voices.stream().map(VoiceDescriptor::of).toList());
    }

    /**
     * One voice. An absent value is left out of the JSON rather than written as {@code null}, so
     * a Gemini voice has no {@code language} key.
     *
     * @param shortName what to pass as {@code voice} together with {@code engine} and {@code language}
     *                  ({@code google-cloud}), or on its own ({@code google-gemini}, where it equals
     *                  {@code fullName})
     * @param fullName  Google's own name, usable as {@code voice} on its own
     * @param engine    canonical engine spelling, or the segment as published for an unrecognized
     *                  engine; for {@code google-gemini}, the entry's model
     * @param language  language-region tag; absent for {@code google-gemini}
     * @param gender    Google's {@code ssmlGender}; absent when Google gives none
     */
    @Schema(name = "TtsVoiceDescriptor")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VoiceDescriptor(String shortName, String fullName, String engine, String language, String gender) {

        static VoiceDescriptor of(CatalogueVoice voice) {
            return new VoiceDescriptor(voice.shortName(), voice.fullName(), voice.engine(), voice.language(),
                    voice.gender());
        }
    }
}
