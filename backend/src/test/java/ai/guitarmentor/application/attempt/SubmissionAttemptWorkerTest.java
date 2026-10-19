package ai.guitarmentor.application.attempt;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.guitarmentor.application.attempt.SubmissionAttemptProcessor.ProcessingJob;
import ai.guitarmentor.infrastructure.media.AudioDurationProbe;
import ai.guitarmentor.application.attempt.AttemptMediaStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SubmissionAttemptWorkerTest {
    @Mock SubmissionAttemptProcessor processor;
    @Mock AttemptMediaStore media;
    @Mock AudioDurationProbe probe;

    @Test
    void rejectsAudioLongerThanLimitWithoutMarkingAsMusicAssessment() throws Exception {
        var id = UUID.randomUUID();
        var job = new ProcessingJob(id, UUID.randomUUID() + ".wav", 400);
        var path = Path.of("test.wav");
        when(processor.claimNext()).thenReturn(Optional.of(job));
        when(media.verifyAndHash(job.key(), job.size())).thenReturn("a".repeat(64));
        when(media.resolveForInspection(job.key())).thenReturn(path);
        when(probe.measure(path)).thenThrow(new IOException("DURATION_EXCEEDED"));

        new SubmissionAttemptWorker(processor, media, probe).poll();
        verify(processor).failure(id, "DURATION_EXCEEDED");
    }

    @Test
    void validAudioTransitionsToTechnicalSuccessWithMeasuredDuration() throws Exception {
        var id = UUID.randomUUID();
        var job = new ProcessingJob(id, UUID.randomUUID() + ".webm", 150);
        var path = Path.of("test.webm");
        when(processor.claimNext()).thenReturn(Optional.of(job));
        when(media.verifyAndHash(job.key(), job.size())).thenReturn("b".repeat(64));
        when(media.resolveForInspection(job.key())).thenReturn(path);
        when(probe.measure(path)).thenReturn(10.4);
        new SubmissionAttemptWorker(processor, media, probe).poll();
        verify(processor).success(id, "b".repeat(64), 10.4);
    }

    @Test
    void unavailableProbeNeverMarksAttemptAsProcessed() throws Exception {
        var id = UUID.randomUUID();
        var job = new ProcessingJob(id, UUID.randomUUID() + ".wav", 150);
        var path = Path.of("test.wav");
        when(processor.claimNext()).thenReturn(Optional.of(job));
        when(media.verifyAndHash(job.key(), job.size())).thenReturn("a".repeat(64));
        when(media.resolveForInspection(job.key())).thenReturn(path);
        when(probe.measure(path)).thenThrow(new IOException("PROBE_UNAVAILABLE"));
        new SubmissionAttemptWorker(processor, media, probe).poll();
        verify(processor).failure(id, "PROBE_UNAVAILABLE");
    }

    @Test
    void absentMediaYieldsStableErrorCode() throws Exception {
        var id = UUID.randomUUID();
        var job = new ProcessingJob(id, UUID.randomUUID() + ".ogg", 128);
        when(processor.claimNext()).thenReturn(Optional.of(job));
        when(media.verifyAndHash(job.key(), job.size())).thenThrow(
                new IOException("MEDIA_MISSING"));
        new SubmissionAttemptWorker(processor, media, probe).poll();
        verify(processor).failure(id, "MEDIA_MISSING");
    }
    @Test
    void m4aMustPassAacOnlyInspectionBeforeItIsMarkedProcessed() throws Exception {
        var id = UUID.randomUUID();
        var job = new ProcessingJob(id, UUID.randomUUID() + ".m4a", 500);
        var path = Path.of("phone.m4a");
        when(processor.claimNext()).thenReturn(Optional.of(job));
        when(media.verifyAndHash(job.key(), job.size())).thenReturn("c".repeat(64));
        when(media.resolveForInspection(job.key())).thenReturn(path);
        when(probe.measure(path)).thenReturn(17.6);
        new SubmissionAttemptWorker(processor, media, probe).poll();
        var order = org.mockito.Mockito.inOrder(probe, processor);
        order.verify(probe).inspectM4a(path);
        order.verify(probe).measure(path);
        order.verify(processor).success(id, "c".repeat(64), 17.6);
    }

    @Test
    void videoInsideM4aCannotBecomeProcessed() throws Exception {
        var id = UUID.randomUUID();
        var job = new ProcessingJob(id, UUID.randomUUID() + ".m4a", 500);
        var path = Path.of("video-renamed.m4a");
        when(processor.claimNext()).thenReturn(Optional.of(job));
        when(media.verifyAndHash(job.key(), job.size())).thenReturn("d".repeat(64));
        when(media.resolveForInspection(job.key())).thenReturn(path);
        org.mockito.Mockito.doThrow(new IOException("INVALID_MEDIA"))
                .when(probe).inspectM4a(path);
        new SubmissionAttemptWorker(processor, media, probe).poll();
        verify(processor).failure(id, "INVALID_MEDIA");
        org.mockito.Mockito.verify(probe, org.mockito.Mockito.never()).measure(path);
    }

}
