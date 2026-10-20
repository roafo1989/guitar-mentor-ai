package ai.guitarmentor.application.evidence;

import static org.mockito.Mockito.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.application.attempt.MediaUseCoordinator;
import ai.guitarmentor.infrastructure.media.AudioDurationProbe;
import ai.guitarmentor.infrastructure.persistence.DiagnosticEvidenceRepository;

class DiagnosticEvidenceWorkerTest {
    private final DiagnosticEvidenceRepository repo=mock(DiagnosticEvidenceRepository.class);
    private final AttemptMediaStore store=mock(AttemptMediaStore.class);
    private final AudioDurationProbe probe=mock(AudioDurationProbe.class);
    private final MediaUseCoordinator guard=mock(MediaUseCoordinator.class);
    private final DiagnosticEvidenceWorker worker=new DiagnosticEvidenceWorker(repo,store,probe,guard);
    private final UUID id=UUID.randomUUID();
    private final DiagnosticEvidenceRepository.AudioJob job=
            new DiagnosticEvidenceRepository.AudioJob(id,"recording.wav",16);

    @BeforeEach void acquire() { when(guard.tryAcquire()).thenReturn(true); }

    @Test void technicalCheckNeverClaimsMusicalAssessment() throws Exception {
        when(repo.claimAudio()).thenReturn(Optional.of(job));
        when(store.resolveForInspection(job.key())).thenReturn(Path.of("temporary.wav"));
        when(probe.measure(any(Path.class))).thenReturn(26.5);
        worker.poll();
        verify(store).verifyAndHash(job.key(),16);
        verify(repo).finishAudio(id,26.5,null);
        verify(guard).release();
    }
    @Test void overlongRecordingIsFailureNotConfirmedSkill() throws Exception {
        when(repo.claimAudio()).thenReturn(Optional.of(job));
        when(store.resolveForInspection(job.key())).thenReturn(Path.of("temporary.wav"));
        when(probe.measure(any(Path.class))).thenThrow(new IOException("DURATION_EXCEEDED"));
        worker.poll();
        verify(repo).finishAudio(id,0,"DURATION_EXCEEDED");
        verify(guard).release();
    }
    @Test void neverProcessesWhileCleanupOwnsGuard() {
        when(guard.tryAcquire()).thenReturn(false);
        worker.poll();
        verifyNoInteractions(repo,store,probe);
        verify(guard,never()).release();
    }
}
