package multiroom.tts.cache;

/**
 * Thrown by {@link AudioCache#put} when the cache write itself fails (disk full, I/O error).
 * Callers fall back to writing the audio to a temporary file outside the cache directory and
 * play from there — this exception exists so that fallback path can be told apart from
 * every other failure.
 */
public class CacheWriteException extends RuntimeException {

    public CacheWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}
