package multiroom.tts.metrics;

import multiroom.tts.config.ProviderType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SynthesisUsageTest {

    @Test
    void countsCodePointsSoAnEmojiIsOneCharacter() {
        SynthesisUsage usage = SynthesisUsage.of("openai", ProviderType.OPENAI, "none", "Hi 😀", null);

        assertThat(usage.textCharacters()).isEqualTo(4);
    }

    @Test
    void aMissingStylePromptCountsAsZero() {
        SynthesisUsage usage = SynthesisUsage.of("openai", ProviderType.OPENAI, "none", "Hello", null);

        assertThat(usage.stylePromptCharacters()).isZero();
        assertThat(usage.billableCharacters()).isEqualTo(5);
    }

    @Test
    void billableCharactersAreTheTextAndTheStylePromptTogether() {
        SynthesisUsage usage = SynthesisUsage.of("gemini", ProviderType.GOOGLE_GEMINI, "gemini-2.5-flash-tts",
                "Hello", "Say it warmly");

        assertThat(usage.providerName()).isEqualTo("gemini");
        assertThat(usage.providerType()).isEqualTo(ProviderType.GOOGLE_GEMINI);
        assertThat(usage.tier()).isEqualTo("gemini-2.5-flash-tts");
        assertThat(usage.stylePromptCharacters()).isEqualTo(13);
        assertThat(usage.billableCharacters()).isEqualTo(18);
    }
}
