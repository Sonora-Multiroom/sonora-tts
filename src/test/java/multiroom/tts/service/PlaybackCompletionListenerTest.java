package multiroom.tts.service;

import multiroom.api.events.RouteDestroyedEvent;
import multiroom.api.model.InputId;
import multiroom.api.model.Route;
import multiroom.api.model.RouteId;
import multiroom.api.model.RouteStatus;
import multiroom.api.model.SampleFormat;
import multiroom.api.model.TargetType;
import multiroom.tts.audio.TtsInputResolver;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheKey;
import multiroom.tts.config.PlaybackMode;
import multiroom.tts.queue.AnnouncementTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PlaybackCompletionListenerTest {

    private static Route routeTo(InputId inputId, String outputName) {
        return Route.builder()
                .routeId(RouteId.of("route-" + outputName))
                .inputId(inputId)
                .targetType(TargetType.SINGLE_OUTPUT)
                .targetId(outputName)
                .status(RouteStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
    }

    private static AnnouncementTask taskFor(InputId inputId, Path audioFile, boolean temporaryFile,
                                             CacheKey cacheKey) {
        return new AnnouncementTask(UUID.randomUUID(), inputId, TargetType.SINGLE_OUTPUT, "living-room",
                audioFile, temporaryFile, PlaybackMode.DUCK_OTHERS, cacheKey);
    }

    @Test
    void matchingEventUnpinsCacheAndReleasesResolver() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        CacheKey cacheKey = new CacheKey("hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, cacheKey);

        listener.track(task, null);
        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        verify(audioCache).unpin(cacheKey);
        verify(inputResolver).release(task.announcementId());
    }

    @Test
    void nonMatchingEventIsIgnored() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, null);
        listener.track(task, null);

        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(InputId.of("some-music-input"), "kitchen")));

        verifyNoInteractions(audioCache);
        verifyNoInteractions(inputResolver);
    }

    @Test
    void duplicateEventForSameAnnouncementIsIdempotent() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        CacheKey cacheKey = new CacheKey("hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, cacheKey);
        listener.track(task, null);

        RouteDestroyedEvent event = new RouteDestroyedEvent(routeTo(announcementInput, "living-room"));
        listener.onRouteDestroyed(event);
        listener.onRouteDestroyed(event);

        verify(audioCache, times(1)).unpin(cacheKey);
        verify(inputResolver, times(1)).release(task.announcementId());
    }

    @Test
    void temporaryFileIsDeletedOnCompletion(@TempDir Path dir) throws Exception {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        Path tempFile = dir.resolve("spilled.wav");
        Files.createFile(tempFile);
        InputId announcementInput = InputId.of("tts-announcement");
        AnnouncementTask task = taskFor(announcementInput, tempFile, true, null);
        listener.track(task, null);

        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        assertThat(Files.exists(tempFile)).isFalse();
    }

    @Test
    void invokesCompletionCallbackAfterReleasing() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, null);
        boolean[] called = {false};
        listener.track(task, () -> called[0] = true);

        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        assertThat(called[0]).isTrue();
    }

    @Test
    void cancelUnpinsCacheAndReleasesResolverWithoutInvokingCallback() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        CacheKey cacheKey = new CacheKey("hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, cacheKey);
        boolean[] called = {false};
        listener.track(task, () -> called[0] = true);

        listener.cancel(announcementInput);

        verify(audioCache).unpin(cacheKey);
        verify(inputResolver).release(task.announcementId());
        assertThat(called[0]).isFalse();
    }

    @Test
    void cancelOnUntrackedInputIsANoOp() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        listener.cancel(InputId.of("never-tracked"));

        verifyNoInteractions(audioCache);
        verifyNoInteractions(inputResolver);
    }

    @Test
    void cancelThenMatchingEventIsIdempotent() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        CacheKey cacheKey = new CacheKey("hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, cacheKey);
        listener.track(task, null);

        listener.cancel(announcementInput);
        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        verify(audioCache, times(1)).unpin(cacheKey);
        verify(inputResolver, times(1)).release(task.announcementId());
    }
}
