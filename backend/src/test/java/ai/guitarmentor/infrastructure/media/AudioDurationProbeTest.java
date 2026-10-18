package ai.guitarmentor.infrastructure.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioDurationProbeTest {
    @Test
    void readsLastAudioPacketInWebMWithoutContainerDuration() throws Exception {
        double seconds = AudioDurationProbe.parsePacketDuration(
            List.of("-0.007000,0.020000", "0.020000,0.020000",
                    "1.000000,0.020000", "1.980000,0.020000"));
        assertEquals(2.0, seconds, 0.0001);
    }

    @Test
    void ignoresUnknownPacketsButRejectsEmptyOrInvalidDuration() {
        assertEquals(1.02, assertValid(List.of("N/A,N/A", "1.0,0.02")), 0.0001);
        assertThrows(IOException.class, () -> AudioDurationProbe.parsePacketDuration(List.of()));
        assertThrows(IOException.class, () -> AudioDurationProbe.parsePacketDuration(List.of("N/A,N/A")));
    }

    private static double assertValid(List<String> packets) {
        try {
            return AudioDurationProbe.parsePacketDuration(packets);
        } catch (IOException problem) {
            throw new AssertionError(problem);
        }
    }
}
