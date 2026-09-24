package multiroom.tts.queue;

import multiroom.tts.TtsErrorCode;
import multiroom.tts.TtsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;

/**
 * Serializes announcements per target: a {@link LinkedBlockingQueue} per target key, with one
 * daemon worker thread spawned on demand and self-terminating once its queue drains (FR-009).
 *
 * <p>Only the routing step is serialized. {@code activator} performs {@code registerInput} +
 * {@code createRoute} for a dequeued task and must call the given {@code Runnable} exactly once
 * playback for that task has actually finished — the worker blocks on it before starting the next
 * queued item, reusing {@code PlaybackCompletionListener}'s {@code RouteDestroyedEvent} signal
 * rather than inventing a second completion mechanism.
 */
public class AnnouncementQueueManager {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementQueueManager.class);

    private final int maxDepthPerTarget;
    private final BiConsumer<AnnouncementTask, Runnable> activator;
    private final Map<String, LinkedBlockingQueue<AnnouncementTask>> queues = new ConcurrentHashMap<>();
    private final Map<String, Thread> workers = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public AnnouncementQueueManager(int maxDepthPerTarget, BiConsumer<AnnouncementTask, Runnable> activator) {
        this.maxDepthPerTarget = maxDepthPerTarget;
        this.activator = activator;
    }

    /**
     * Adds {@code task} to its target's queue, starting a worker if none is active.
     *
     * @return the queue depth for this target immediately after enqueuing, including this task
     * @throws TtsException {@link TtsErrorCode#PROVIDER_ERROR} if the queue is full or shutting down
     */
    public int enqueue(String targetKey, AnnouncementTask task) {
        if (!running) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR, "TTS extension is shutting down");
        }
        LinkedBlockingQueue<AnnouncementTask> queue = queues.computeIfAbsent(targetKey,
                key -> new LinkedBlockingQueue<>(maxDepthPerTarget));
        if (!queue.offer(task)) {
            throw new TtsException(TtsErrorCode.PROVIDER_ERROR,
                    "Announcement queue for '" + targetKey + "' is full");
        }
        ensureWorkerRunning(targetKey, queue);
        return queue.size();
    }

    private void ensureWorkerRunning(String targetKey, LinkedBlockingQueue<AnnouncementTask> queue) {
        workers.computeIfAbsent(targetKey, key -> {
            Thread worker = new Thread(() -> runWorker(targetKey, queue), "tts-queue-" + targetKey);
            worker.setDaemon(true);
            worker.start();
            return worker;
        });
    }

    private void runWorker(String targetKey, LinkedBlockingQueue<AnnouncementTask> queue) {
        while (true) {
            AnnouncementTask task = queue.poll();
            if (task == null) {
                workers.remove(targetKey);
                if (!queue.isEmpty()) {
                    // An item slipped in between poll() and removal; restart to pick it up.
                    ensureWorkerRunning(targetKey, queue);
                }
                return;
            }
            if (!activate(task)) {
                continue;
            }
            if (!running) {
                return;
            }
        }
    }

    /** Returns {@code false} if activation itself failed, so the worker can move on immediately. */
    private boolean activate(AnnouncementTask task) {
        CountDownLatch completed = new CountDownLatch(1);
        try {
            activator.accept(task, completed::countDown);
        } catch (RuntimeException e) {
            log.error("Failed to activate queued announcement {}", task.announcementId(), e);
            return false;
        }
        try {
            completed.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /** Stops accepting new work; queued-but-not-started items are discarded, the current one finishes. */
    public void stop() {
        running = false;
        queues.values().forEach(LinkedBlockingQueue::clear);
    }

    public void start() {
        running = true;
    }

    public boolean isRunning() {
        return running;
    }
}
