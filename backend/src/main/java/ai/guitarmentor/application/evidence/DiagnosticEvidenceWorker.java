package ai.guitarmentor.application.evidence;

import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.application.attempt.MediaUseCoordinator;
import ai.guitarmentor.infrastructure.media.AudioDurationProbe;
import ai.guitarmentor.infrastructure.persistence.DiagnosticEvidenceRepository;
import java.io.IOException;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Technical validation only. Uploading is NOT a musical skills assessment. */
@Component
@ConditionalOnProperty(prefix="guitar-mentor.submissions",name="enabled",havingValue="true")
public class DiagnosticEvidenceWorker {
    private final DiagnosticEvidenceRepository evidence;
    private final AttemptMediaStore audio;
    private final AudioDurationProbe probe;
    private final MediaUseCoordinator coordinator;
    public DiagnosticEvidenceWorker(DiagnosticEvidenceRepository evidence,
            AttemptMediaStore audio, AudioDurationProbe probe, MediaUseCoordinator coordinator) {
        this.evidence=evidence;this.audio=audio;this.probe=probe;this.coordinator=coordinator;
    }
    @Scheduled(fixedDelayString="$"+"{guitar-mentor.submissions.poll-delay-ms:3000}")
    public void poll() {
        if (!coordinator.tryAcquire()) return;
        try {
            evidence.recover(Instant.now().minusSeconds(300));
            var job=evidence.claimAudio();
            if (job.isEmpty()) return;
            var item=job.get();
            try {
                audio.verifyAndHash(item.key(),item.bytes());
                double duration=probe.measure(audio.resolveForInspection(item.key()));
                evidence.finishAudio(item.id(),duration,null);
            } catch (IOException failure) {
                String reason=failure.getMessage();
                String code=java.util.Set.of("MEDIA_MISSING","SIZE_MISMATCH","INVALID_FORMAT",
                    "INVALID_KEY","INVALID_MEDIA","DURATION_EXCEEDED","PROBE_UNAVAILABLE",
                    "PROBE_TIMEOUT","PROBE_INTERRUPTED").contains(reason)
                        ? reason : "IO_ERROR";
                evidence.finishAudio(item.id(),0,code);
            } catch (RuntimeException unexpected) {
                evidence.finishAudio(item.id(),0,"TECHNICAL_ERROR");
            }
        } finally { coordinator.release(); }
    }
}
