package multiroom.tts.audio;

import multiroom.api.io.resolve.EndpointRefreshMode;
import multiroom.api.io.resolve.InputResolutionException;
import multiroom.api.io.resolve.InputResolutionFailure;
import multiroom.api.io.resolve.InputEndpointResolver;
import multiroom.api.io.resolve.ResolvedInputEndpoint;
import multiroom.api.model.AudioInputDefinition;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rewrites {@code tts://<uuid>} to the {@code file://} URI of the announcement's WAV on disk.
 *
 * <p>A resolver only rewrites a URI; core then looks the resulting <em>scheme</em> up among
 * {@code InputHandler} beans, and {@code InputHandler} lives in {@code multiroom.core.io}, a
 * package this module may not depend on. There is therefore no {@code tts://} handler to hand
 * bytes to, so this resolver must terminate on a scheme core already handles — {@code file://},
 * played by core's existing {@code WavFileInputHandler}.
 */
public class TtsInputResolver implements InputEndpointResolver {

    private static final String SCHEME = "tts";

    private final Map<UUID, Path> filesByAnnouncement = new ConcurrentHashMap<>();

    /** Registers the WAV file an in-flight announcement's {@code tts://<uuid>} resolves to. */
    public void register(UUID announcementId, Path wavFile) {
        filesByAnnouncement.put(announcementId, wavFile);
    }

    /** Drops the mapping once playback has finished, so the map does not grow without bound. */
    public void release(UUID announcementId) {
        filesByAnnouncement.remove(announcementId);
    }

    @Override
    public boolean supports(AudioInputDefinition input) {
        return input.getUri() != null && input.getUri().startsWith(SCHEME + "://");
    }

    @Override
    public ResolvedInputEndpoint resolve(AudioInputDefinition input) throws InputResolutionException {
        UUID announcementId = parseAnnouncementId(input.getUri());
        Path wavFile = filesByAnnouncement.get(announcementId);
        if (wavFile == null) {
            throw new InputResolutionException(InputResolutionFailure.INVALID_INPUT,
                    "Unknown TTS announcement id: " + announcementId);
        }
        if (!Files.exists(wavFile)) {
            throw new InputResolutionException(InputResolutionFailure.UPSTREAM_UNAVAILABLE,
                    "Audio file for TTS announcement " + announcementId + " is no longer available");
        }
        return new ResolvedInputEndpoint(input, wavFile.toUri(), Map.of(), null,
                EndpointRefreshMode.NONE, SCHEME);
    }

    private UUID parseAnnouncementId(String uri) {
        try {
            URI parsed = URI.create(uri);
            String idPart = parsed.getHost() != null ? parsed.getHost() : parsed.getSchemeSpecificPart();
            return UUID.fromString(idPart.replace("/", ""));
        } catch (IllegalArgumentException e) {
            throw new InputResolutionException(InputResolutionFailure.INVALID_INPUT,
                    "Malformed tts:// URI: " + uri, e);
        }
    }
}
