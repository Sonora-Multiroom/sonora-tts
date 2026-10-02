package multiroom.tts.config;

import multiroom.api.model.RouteJoinMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybackModeTest {

    @ParameterizedTest
    @ValueSource(strings = {"duck-others", "DUCK-OTHERS", "Duck-Others"})
    void duckOthersIsAcceptedInAnyCase(String name) {
        assertThat(PlaybackMode.fromName(name)).contains(PlaybackMode.DUCK_OTHERS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mix", "MIX"})
    void mixIsAcceptedInAnyCase(String name) {
        assertThat(PlaybackMode.fromName(name)).contains(PlaybackMode.MIX);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"replace", "duck_others", "duckothers", "", "  "})
    void anythingElseIsRejected(String name) {
        assertThat(PlaybackMode.fromName(name)).isEmpty();
    }

    @Test
    void configNameIsLowerCaseWithHyphens() {
        assertThat(PlaybackMode.DUCK_OTHERS.configName()).isEqualTo("duck-others");
        assertThat(PlaybackMode.MIX.configName()).isEqualTo("mix");
    }

    @Test
    void joinModeMapsBothValues() {
        assertThat(PlaybackMode.DUCK_OTHERS.joinMode()).isEqualTo(RouteJoinMode.DUCK_OTHERS);
        assertThat(PlaybackMode.MIX.joinMode()).isEqualTo(RouteJoinMode.MIX);
    }

    @Test
    void supportedListNamesBoth() {
        assertThat(PlaybackMode.supportedList()).isEqualTo("duck-others, mix");
    }
}
