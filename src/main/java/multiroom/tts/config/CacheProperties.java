package multiroom.tts.config;

import lombok.Data;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Bound from {@code multiroom.tts.cache}. */
@Data
public class CacheProperties {

    private Path dir = Paths.get(System.getProperty("user.home"), ".multiroom", "tts-cache");

    private int maxSizeMb = 500;
}
