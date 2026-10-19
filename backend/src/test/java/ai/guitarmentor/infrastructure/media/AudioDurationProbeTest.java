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
    @Test
    void acceptsM4aOnlyWhenMovFamilyAndAacAudioStreamPresent() throws Exception {
        AudioDurationProbe.validateM4aStreams(List.of(
                "codec_name=aac|codec_type=audio",
                "format_name=mov,mp4,m4a,3gp,3g2,mj2"));
    }

    @Test
    void rejectsM4aVideoSubtitlesWrongCodecMissingAudioAndMissingContainer() {
        for (List<String> output : List.of(
                List.of("codec_name=aac|codec_type=audio",
                        "codec_name=h264|codec_type=video",
                        "format_name=mov,mp4,m4a,3gp,3g2,mj2"),
                List.of("codec_name=h264|codec_type=video",
                        "format_name=mov,mp4,m4a,3gp,3g2,mj2"),
                List.of("codec_name=alac|codec_type=audio",
                        "format_name=mov,mp4,m4a,3gp,3g2,mj2"),
                List.of("codec_name=aac|codec_type=audio",
                        "codec_name=bin_data|codec_type=data",
                        "format_name=mov,mp4,m4a,3gp,3g2,mj2"),
                List.of("codec_name=aac|codec_type=audio"),
                List.of("format_name=mov,mp4,m4a,3gp,3g2,mj2"),
                List.of("codec_name=aac|codec_type=audio", "format_name=matroska,webm")
        )) {
            var rejected = assertThrows(IOException.class,
                    () -> AudioDurationProbe.validateM4aStreams(output));
            assertEquals("INVALID_MEDIA", rejected.getMessage());
        }
    }

    @Test
    void inspectsGenerated3gp4M4aAacWithRealFfprobeIfAvailable(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var media = temp.resolve("generated.m4a");
        Process encoder;
        try {
            encoder = new ProcessBuilder("ffmpeg", "-nostdin", "-hide_banner",
                    "-loglevel", "error", "-f", "lavfi", "-i",
                    "anullsrc=channel_layout=mono:sample_rate=44100", "-t", "0.5",
                    "-c:a", "aac", "-b:a", "64k", "-brand", "3gp4",
                    "-y", media.toString()).start();
        } catch (IOException absent) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "ffmpeg unavailable");
            return;
        }
        assertEquals(0, encoder.waitFor());
        var probe = new AudioDurationProbe("ffprobe");
        try {
            probe.inspectM4a(media);
        } catch (IOException unavailable) {
            if ("PROBE_UNAVAILABLE".equals(unavailable.getMessage())) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "ffprobe unavailable");
            }
            throw unavailable;
        }
        assertEquals(0.5, probe.measure(media), 0.05);
    }

}
