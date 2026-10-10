package multiroom.tts.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
                .startedAt(Instant.now())
                .build();
    }

    /** A route the hub admitted and then destroyed because it failed to start: no start time. */
    private static Route neverStartedRouteTo(InputId inputId, String outputName) {
        return Route.builder()
                .routeId(RouteId.of("route-" + outputName))
                .inputId(inputId)
                .targetType(TargetType.SINGLE_OUTPUT)
                .targetId(outputName)
                .status(RouteStatus.STOPPING)
                .createdAt(Instant.now())
                .build();
    }

    private static List<ILoggingEvent> captureListenerLog(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PlaybackCompletionListener.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        return appender.list;
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

        boolean removed = listener.cancel(announcementInput);

        assertThat(removed).isTrue();
        verify(audioCache).unpin(cacheKey);
        verify(inputResolver).release(task.announcementId());
        assertThat(called[0]).isFalse();
    }

    @Test
    void cancelOnUntrackedInputIsANoOp() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        boolean removed = listener.cancel(InputId.of("never-tracked"));

        assertThat(removed).isFalse();
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

    @Test
    void aSecondCancelForTheSameInputRemovesNothing() {
        PlaybackCompletionListener listener =
                new PlaybackCompletionListener(mock(AudioCache.class), mock(TtsInputResolver.class));
        InputId announcementInput = InputId.of("tts-announcement");
        listener.track(taskFor(announcementInput, Path.of("cache/a.wav"), false, null), null);

        assertThat(listener.cancel(announcementInput)).isTrue();
        assertThat(listener.cancel(announcementInput)).isFalse();
    }

    @Test
    void cancelAfterACompletedAnnouncementRemovesNothing() {
        PlaybackCompletionListener listener =
                new PlaybackCompletionListener(mock(AudioCache.class), mock(TtsInputResolver.class));
        InputId announcementInput = InputId.of("tts-announcement");
        listener.track(taskFor(announcementInput, Path.of("cache/a.wav"), false, null), null);
        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        assertThat(listener.cancel(announcementInput)).isFalse();
    }

    @Test
    void cancelDeletesATemporaryAudioFile(@TempDir Path dir) throws Exception {
        PlaybackCompletionListener listener =
                new PlaybackCompletionListener(mock(AudioCache.class), mock(TtsInputResolver.class));
        Path tempFile = dir.resolve("spilled.wav");
        Files.createFile(tempFile);
        InputId announcementInput = InputId.of("tts-announcement");
        listener.track(taskFor(announcementInput, tempFile, true, null), null);

        listener.cancel(announcementInput);

        assertThat(Files.exists(tempFile)).isFalse();
    }

    @Test
    void aRouteThatNeverStartedIsLeftTrackedForTheFailurePath() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);
        InputId announcementInput = InputId.of("tts-announcement");
        CacheKey cacheKey = new CacheKey("hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        boolean[] called = {false};
        listener.track(taskFor(announcementInput, Path.of("cache/a.wav"), false, cacheKey),
                () -> called[0] = true);

        List<ILoggingEvent> events = captureListenerLog(() -> listener.onRouteDestroyed(
                new RouteDestroyedEvent(neverStartedRouteTo(announcementInput, "living-room"))));

        verifyNoInteractions(audioCache);
        verifyNoInteractions(inputResolver);
        assertThat(called[0]).isFalse();
        assertThat(events).noneMatch(e -> e.getFormattedMessage().startsWith("TTS_PLAYBACK_COMPLETED"));
        assertThat(listener.cancel(announcementInput)).isTrue();
    }

    @Test
    void aNeverStartedRouteOfAnUntrackedInputIsIgnored() {
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(audioCache, inputResolver);

        listener.onRouteDestroyed(
                new RouteDestroyedEvent(neverStartedRouteTo(InputId.of("some-music-input"), "kitchen")));

        verifyNoInteractions(audioCache);
        verifyNoInteractions(inputResolver);
    }

    @Test
    void aNeverStartedEventFollowedByAStartedOneCompletesOnce() {
        PlaybackCompletionListener listener =
                new PlaybackCompletionListener(mock(AudioCache.class), mock(TtsInputResolver.class));
        InputId announcementInput = InputId.of("tts-announcement");
        int[] calls = {0};
        listener.track(taskFor(announcementInput, Path.of("cache/a.wav"), false, null), () -> calls[0]++);

        listener.onRouteDestroyed(new RouteDestroyedEvent(neverStartedRouteTo(announcementInput, "living-room")));
        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        assertThat(calls[0]).isEqualTo(1);
    }
}
