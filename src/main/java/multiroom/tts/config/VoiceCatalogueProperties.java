package multiroom.tts.config;

import lombok.Data;

import java.time.Duration;

/**
 * Timings of each {@code google-cloud} entry's voice catalogue, bound from
 * {@code multiroom.tts.voice-catalogue}. Every entry has its own catalogue instance; all
 * of them share these timings.
 */
@Data
public class VoiceCatalogueProperties {

    /** How long a fetched catalogue is trusted before it is fetched again. */
    private Duration ttl = Duration.ofHours(24);

    /**
     * After a failed fetch, no new fetch for this long: an outage costs at most one slow request
     * per period. Also the minimum age of a catalogue before a voice missing from it
     * triggers a refetch, so repeated typos cannot force a download each time.
     */
    private Duration failureBackoff = Duration.ofSeconds(60);

    /**
     * Cap on one catalogue fetch. It is taken out of the entry's {@code timeout-seconds}, and is
     * kept well below it so that a hung catalogue still leaves time to synthesize.
     */
    private Duration fetchTimeout = Duration.ofSeconds(3);
}
