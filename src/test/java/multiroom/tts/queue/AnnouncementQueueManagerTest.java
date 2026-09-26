package multiroom.tts.queue;

import multiroom.api.model.InputId;
import multiroom.api.model.TargetType;
import multiroom.tts.TtsErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class AnnouncementQueueManagerTest {

    private static AnnouncementTask dummyTask(String targetName) {
        UUID id = UUID.randomUUID();
        return new AnnouncementTask(id, InputId.of("tts-" + id), TargetType.SINGLE_OUTPUT, targetName,
                Path.of("audio.wav"), false, List.of(), null);
    }

    @Test
    @Timeout(5)
    void twoRequestsToOneTargetPlayInSubmissionOrder() throws Exception {
        List<UUID> playedOrder = new CopyOnWriteArrayList<>();
        AnnouncementQueueManager manager = new AnnouncementQueueManager(10,
                (task, onComplete) -> {
                    playedOrder.add(task.announcementId());
                    onComplete.run();
                });

        AnnouncementTask first = dummyTask("kitchen");
        AnnouncementTask second = dummyTask("kitchen");
        manager.enqueue("kitchen", first);
        manager.enqueue("kitchen", second);

        await().atMost(2, TimeUnit.SECONDS).until(() -> playedOrder.size() == 2);
        assertThat(playedOrder).containsExactly(first.announcementId(), second.announcementId());
    }

    @Test
    @Timeout(5)
    void twoTargetsProceedIndependently() throws Exception {
        CountDownLatch releaseKitchen = new CountDownLatch(1);
        Set<String> activated = java.util.concurrent.ConcurrentHashMap.newKeySet();
        AnnouncementQueueManager manager = new AnnouncementQueueManager(10, (task, onComplete) -> {
            activated.add(task.targetName());
            if (task.targetName().equals("kitchen")) {
                new Thread(() -> {
                    try {
                        releaseKitchen.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    onComplete.run();
                }).start();
            } else {
                onComplete.run();
            }
        });

        manager.enqueue("kitchen", dummyTask("kitchen"));
        manager.enqueue("living-room", dummyTask("living-room"));

        await().atMost(2, TimeUnit.SECONDS).until(() -> activated.contains("living-room"));
        releaseKitchen.countDown();
    }

    @Test
    @Timeout(5)
    void shutdownDiscardsQueuedItemsButLetsCurrentOneFinish() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        List<UUID> activated = new CopyOnWriteArrayList<>();
        AnnouncementQueueManager manager = new AnnouncementQueueManager(10, (task, onComplete) -> {
            activated.add(task.announcementId());
            new Thread(() -> {
                try {
                    releaseFirst.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                onComplete.run();
            }).start();
        });

        AnnouncementTask first = dummyTask("kitchen");
        AnnouncementTask second = dummyTask("kitchen");
        manager.enqueue("kitchen", first);
        await().atMost(2, TimeUnit.SECONDS).until(() -> activated.contains(first.announcementId()));
        manager.enqueue("kitchen", second);

        manager.stop();
        releaseFirst.countDown();

        Thread.sleep(300);
        assertThat(activated).containsExactly(first.announcementId());
    }

    /** A manager whose activator holds every announcement until {@code release} opens. */
    private static AnnouncementQueueManager blockingManager(int maxDepth, CountDownLatch release,
                                                            List<UUID> activated) {
        return new AnnouncementQueueManager(maxDepth, (task, onComplete) -> {
            activated.add(task.announcementId());
            new Thread(() -> {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                onComplete.run();
            }).start();
        });
    }

    @Test
    @Timeout(5)
    void aFullQueueRejectsWithItsReasonAndTheUnchangedCodeAndMessage() {
        CountDownLatch release = new CountDownLatch(1);
        List<UUID> activated = new CopyOnWriteArrayList<>();
        AnnouncementQueueManager manager = blockingManager(1, release, activated);
        AnnouncementTask playing = dummyTask("kitchen");
        manager.enqueue("kitchen", playing);
        await().atMost(2, TimeUnit.SECONDS).until(() -> activated.contains(playing.announcementId()));
        manager.enqueue("kitchen", dummyTask("kitchen"));

        assertThatThrownBy(() -> manager.enqueue("kitchen", dummyTask("kitchen")))
                .isInstanceOfSatisfying(QueueRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(QueueRejectedException.Reason.QUEUE_FULL);
                    assertThat(e.tagValue()).isEqualTo("queue_full");
                    assertThat(e.getErrorCode()).isEqualTo(TtsErrorCode.PROVIDER_ERROR);
                    assertThat(e.getMessage()).isEqualTo("Announcement queue for 'kitchen' is full");
                });
        release.countDown();
    }

    @Test
    void anEnqueueAfterStopRejectsAsShuttingDown() {
        AnnouncementQueueManager manager = new AnnouncementQueueManager(10, (task, onComplete) -> onComplete.run());
        manager.stop();

        assertThatThrownBy(() -> manager.enqueue("kitchen", dummyTask("kitchen")))
                .isInstanceOfSatisfying(QueueRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(QueueRejectedException.Reason.SHUTTING_DOWN);
                    assertThat(e.tagValue()).isEqualTo("shutting_down");
                    assertThat(e.getErrorCode()).isEqualTo(TtsErrorCode.PROVIDER_ERROR);
                    assertThat(e.getMessage()).isEqualTo("TTS extension is shutting down");
                });
    }

    @Test
    @Timeout(5)
    void depthCountsOnlyTheAnnouncementsStillWaiting() {
        CountDownLatch release = new CountDownLatch(1);
        List<UUID> activated = new CopyOnWriteArrayList<>();
        AnnouncementQueueManager manager = blockingManager(10, release, activated);
        assertThat(manager.depth("kitchen")).isZero();

        AnnouncementTask playing = dummyTask("kitchen");
        manager.enqueue("kitchen", playing);
        await().atMost(2, TimeUnit.SECONDS).until(() -> activated.contains(playing.announcementId()));
        assertThat(manager.depth("kitchen")).isZero();

        manager.enqueue("kitchen", dummyTask("kitchen"));
        manager.enqueue("kitchen", dummyTask("kitchen"));
        assertThat(manager.depth("kitchen")).isEqualTo(2);
        assertThat(manager.depth("living-room")).isZero();

        release.countDown();
        await().atMost(2, TimeUnit.SECONDS).until(() -> manager.depth("kitchen") == 0);
    }
}
