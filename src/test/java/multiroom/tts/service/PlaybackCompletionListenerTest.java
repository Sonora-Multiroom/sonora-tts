package multiroom.tts.service;

import multiroom.api.events.RouteDestroyedEvent;
import multiroom.api.model.GroupId;
import multiroom.api.model.InputId;
import multiroom.api.model.OutputId;
import multiroom.api.model.Route;
import multiroom.api.model.RouteId;
import multiroom.api.model.RouteStatus;
import multiroom.api.model.SampleFormat;
import multiroom.api.model.TargetType;
import multiroom.api.services.RouteService;
import multiroom.tts.audio.TtsInputResolver;
import multiroom.tts.cache.AudioCache;
import multiroom.tts.cache.CacheKey;
import multiroom.tts.queue.AnnouncementTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                                             List<Route> snapshot, CacheKey cacheKey) {
        return new AnnouncementTask(UUID.randomUUID(), inputId, TargetType.SINGLE_OUTPUT, "living-room",
                audioFile, temporaryFile, snapshot, cacheKey);
    }

    @Test
    void matchingEventRestoresSnapshottedRoutesAndUnpinsCache() {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        InputId originalInput = InputId.of("music-input");
        CacheKey cacheKey = new CacheKey("hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false,
                List.of(routeTo(originalInput, "living-room")), cacheKey);

        listener.track(task, null);
        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        verify(routeService).createRoute(originalInput, OutputId.of("living-room"));
        verify(audioCache).unpin(cacheKey);
        verify(inputResolver).release(task.announcementId());
    }

    @Test
    void nonMatchingEventIsIgnored() {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, List.of(), null);
        listener.track(task, null);

        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(InputId.of("some-music-input"), "kitchen")));

        verifyNoInteractions(routeService);
        verifyNoInteractions(audioCache);
    }

    @Test
    void duplicateEventForSameAnnouncementIsIdempotent() {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        InputId originalInput = InputId.of("music-input");
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false,
                List.of(routeTo(originalInput, "living-room")), null);
        listener.track(task, null);

        RouteDestroyedEvent event = new RouteDestroyedEvent(routeTo(announcementInput, "living-room"));
        listener.onRouteDestroyed(event);
        listener.onRouteDestroyed(event);

        verify(routeService, org.mockito.Mockito.times(1)).createRoute(originalInput, OutputId.of("living-room"));
    }

    @Test
    void emptySnapshotRestoresNothing() {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, List.of(), null);
        listener.track(task, null);

        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        verify(routeService, never()).createRoute(org.mockito.ArgumentMatchers.any(InputId.class), org.mockito.ArgumentMatchers.any(OutputId.class));
    }

    @Test
    void temporaryFileIsDeletedOnCompletion(@TempDir Path dir) throws Exception {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        Path tempFile = dir.resolve("spilled.wav");
        Files.createFile(tempFile);
        InputId announcementInput = InputId.of("tts-announcement");
        AnnouncementTask task = taskFor(announcementInput, tempFile, true, List.of(), null);
        listener.track(task, null);

        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        assertThat(Files.exists(tempFile)).isFalse();
    }

    @Test
    void invokesCompletionCallbackAfterRestoring() {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false, List.of(), null);
        boolean[] called = {false};
        listener.track(task, () -> called[0] = true);

        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        assertThat(called[0]).isTrue();
    }

    @Test
    void cancelRestoresRoutesUnpinsCacheAndReleasesResolverWithoutInvokingCallback() {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        InputId originalInput = InputId.of("music-input");
        CacheKey cacheKey = new CacheKey("hi", "openai", "tts-1", "alloy", "en-US", SampleFormat.standard());
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false,
                List.of(routeTo(originalInput, "living-room")), cacheKey);
        boolean[] called = {false};
        listener.track(task, () -> called[0] = true);

        listener.cancel(announcementInput);

        verify(routeService).createRoute(originalInput, OutputId.of("living-room"));
        verify(audioCache).unpin(cacheKey);
        verify(inputResolver).release(task.announcementId());
        assertThat(called[0]).isFalse();
    }

    @Test
    void cancelOnUntrackedInputIsANoOp() {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        listener.cancel(InputId.of("never-tracked"));

        verifyNoInteractions(routeService);
        verifyNoInteractions(audioCache);
        verifyNoInteractions(inputResolver);
    }

    @Test
    void cancelThenMatchingEventIsIdempotent() {
        RouteService routeService = mock(RouteService.class);
        AudioCache audioCache = mock(AudioCache.class);
        TtsInputResolver inputResolver = mock(TtsInputResolver.class);
        PlaybackCompletionListener listener = new PlaybackCompletionListener(routeService, audioCache, inputResolver);

        InputId announcementInput = InputId.of("tts-announcement");
        InputId originalInput = InputId.of("music-input");
        AnnouncementTask task = taskFor(announcementInput, Path.of("cache/a.wav"), false,
                List.of(routeTo(originalInput, "living-room")), null);
        listener.track(task, null);

        listener.cancel(announcementInput);
        listener.onRouteDestroyed(new RouteDestroyedEvent(routeTo(announcementInput, "living-room")));

        verify(routeService, org.mockito.Mockito.times(1)).createRoute(originalInput, OutputId.of("living-room"));
    }
}
