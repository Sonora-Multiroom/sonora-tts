package multiroom.tts.rest;

import io.swagger.v3.oas.annotations.media.Schema;
import multiroom.tts.provider.cloud.google.CatalogueVoice;

import java.util.List;

/**
 * {@code GET /api/tts/providers/{name}/voices} response, matching {@code contracts/tts-rest-api.yaml}.
 * Named {@code TtsVoiceListResponse} in the generated OpenAPI document for the same reason as
 * {@link ErrorResponse}: springdoc keys schemas by simple class name across the shared classpath.
 *
 * @param providerName the provider the voices belong to
 * @param voices       the matching voices, sorted by language, engine, then short name
 */
@Schema(name = "TtsVoiceListResponse")
public record VoiceListResponse(String providerName, List<VoiceDescriptor> voices) {

    static VoiceListResponse of(String providerName, List<CatalogueVoice> voices) {
        return new VoiceListResponse(providerName, voices.stream().map(VoiceDescriptor::of).toList());
    }

    /**
     * One voice.
     *
     * @param shortName what to pass as {@code voice} together with {@code engine} and {@code language}
     * @param fullName  Google's own name, usable as {@code voice} on its own
     * @param engine    canonical engine spelling, or the segment as published for an unrecognized engine
     * @param language  language-region tag
     */
    @Schema(name = "TtsVoiceDescriptor")
    public record VoiceDescriptor(String shortName, String fullName, String engine, String language) {

        static VoiceDescriptor of(CatalogueVoice voice) {
            return new VoiceDescriptor(voice.shortName(), voice.fullName(), voice.engine(), voice.language());
        }
    }
}
