package multiroom.tts;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * This module ships no {@code @SpringBootApplication} of its own — the host supplies it. Web
 * slice tests ({@code @WebMvcTest}) still need one discoverable by package-upward search to
 * bootstrap their minimal context and, critically, to component-scan {@code multiroom.tts.rest}
 * for the controllers and {@code @RestControllerAdvice} under test.
 */
@SpringBootApplication
class TestApplication {
}
