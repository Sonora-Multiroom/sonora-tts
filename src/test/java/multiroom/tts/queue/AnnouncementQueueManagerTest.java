package multiroom.tts.queue;

import multiroom.api.model.InputId;
import multiroom.api.model.TargetType;
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
}
