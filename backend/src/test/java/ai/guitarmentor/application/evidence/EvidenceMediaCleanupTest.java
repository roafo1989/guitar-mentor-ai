package ai.guitarmentor.application.evidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.application.attempt.MediaRetentionPolicy;
import ai.guitarmentor.infrastructure.media.DocumentMediaStore;
import ai.guitarmentor.infrastructure.persistence.DiagnosticEvidenceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EvidenceMediaCleanupTest {
    private final DiagnosticEvidenceRepository db=mock(DiagnosticEvidenceRepository.class);
    private final AttemptMediaStore audio=mock(AttemptMediaStore.class);
    private final DocumentMediaStore documents=mock(DocumentMediaStore.class);
    private final EvidenceMediaCleanup cleanup=new EvidenceMediaCleanup(db,audio,documents);
    private final MediaRetentionPolicy.Policy policy=new MediaRetentionPolicy.Policy(
            Duration.ofHours(48),Duration.ofHours(48),Duration.ofMinutes(60));

    @Test void deletesBothKindsButRetainsMetadata() throws Exception {
        Instant now=Instant.parse("2026-10-19T12:00:00Z");
        UUID id1=UUID.randomUUID(), id2=UUID.randomUUID();
        when(db.claimExpired(now.minus(policy.afterFinal()),now.minus(policy.maxUnprocessed())))
                .thenReturn(List.of(new DiagnosticEvidenceRepository.Expired(id1,"AUDIO","a.wav"),
                        new DiagnosticEvidenceRepository.Expired(id2,"SCORE","b.pdf")));
        cleanup.runCycle(now,policy);
        verify(audio).delete("a.wav");
        verify(documents).delete("b.pdf");
        verify(db).markDeleted(id1);
        verify(db).markDeleted(id2);
    }

    @Test void failedDeletionNeverMarksFileDeleted() throws Exception {
        Instant now=Instant.parse("2026-10-19T12:00:00Z");
        UUID id=UUID.randomUUID();
        when(db.claimExpired(any(),any())).thenReturn(
                List.of(new DiagnosticEvidenceRepository.Expired(id,"SCORE","b.pdf")));
        when(documents.delete("b.pdf")).thenThrow(new java.io.IOException("disk failed"));
        cleanup.runCycle(now,policy);
        verify(db).markFailed(id);
        verify(db,never()).markDeleted(any());
    }
    @Test void referencedScoreOrphanIsProtected() throws Exception {
        Instant now=Instant.parse("2026-10-19T12:00:00Z");
        when(documents.oldCandidates(any())).thenReturn(List.of("old.pdf"));
        when(db.existsByKey("old.pdf")).thenReturn(true);
        cleanup.reconcileDocumentOrphans(now,policy);
        verify(documents,never()).delete(any());
    }
}
