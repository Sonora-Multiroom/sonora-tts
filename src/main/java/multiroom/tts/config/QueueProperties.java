package multiroom.tts.config;

import lombok.Data;

/** Bound from {@code multiroom.tts.queue}. */
@Data
public class QueueProperties {

    private int maxDepthPerTarget = 10;
}
