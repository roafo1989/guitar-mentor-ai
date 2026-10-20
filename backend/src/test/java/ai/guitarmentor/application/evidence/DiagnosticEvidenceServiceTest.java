package ai.guitarmentor.application.evidence;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.guitarmentor.application.attempt.AttemptMediaStore;
import ai.guitarmentor.application.attempt.MediaTransactionGuard;
import ai.guitarmentor.application.attempt.MediaUseCoordinator;
import ai.guitarmentor.application.diagnostic.DiagnosticSessionService;
import ai.guitarmentor.domain.diagnostic.DiagnosticSession;
import ai.guitarmentor.domain.diagnostic.DiagnosticStatus;
import ai.guitarmentor.infrastructure.media.DocumentMediaStore;
import ai.guitarmentor.infrastructure.persistence.DiagnosticEvidenceRepository;
import ai.guitarmentor.infrastructure.persistence.DiagnosticSessionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

class DiagnosticEvidenceServiceTest {
    private final UUID learner=UUID.randomUUID(), sessionId=UUID.randomUUID(),
            request=UUID.randomUUID();
    private final DiagnosticSessionService diagnostics=mock(DiagnosticSessionService.class);
    private final DiagnosticSessionRepository sessions=mock(DiagnosticSessionRepository.class);
    private final DiagnosticEvidenceRepository repo=mock(DiagnosticEvidenceRepository.class);
    private final AttemptMediaStore audio=mock(AttemptMediaStore.class);
    private final DocumentMediaStore scores=mock(DocumentMediaStore.class);
    private final MediaTransactionGuard rollback=mock(MediaTransactionGuard.class);
    private final MediaUseCoordinator coordinator=mock(MediaUseCoordinator.class);
    private final DiagnosticSession session=mock(DiagnosticSession.class);
    private final DiagnosticEvidenceService service=new DiagnosticEvidenceService(diagnostics,
            sessions,repo,audio,scores,rollback,coordinator,true);
    private final MockMultipartFile recording=new MockMultipartFile("audio","test.wav",
            "audio/wav",new byte[]{'R','I','F','F',0,0,0,0,'W','A','V','E'});

    @BeforeEach void setup() {
        TransactionSynchronizationManager.initSynchronization();
        when(sessions.findLockedOwnedById(learner,sessionId)).thenReturn(Optional.of(session));
        when(session.getStatus()).thenReturn(DiagnosticStatus.COLLECTING_EVIDENCE);
        when(repo.byRequest(sessionId,request)).thenReturn(Optional.empty());
        when(audio.store(recording)).thenReturn(
                new AttemptMediaStore.StoredMedia(UUID.randomUUID()+".wav","wav",12));
    }
    @AfterEach void finish() {
        for (var callback:TransactionSynchronizationManager.getSynchronizations())
            callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        TransactionSynchronizationManager.clearSynchronization();
    }
    @Test void capabilityFormatsFollowExistingAudioStoreIncludingM4a() {
        var capabilities=service.capabilities(learner,sessionId);
        assertTrue(capabilities.audioFormats().contains("m4a"));
        assertTrue(capabilities.audioFormats().contains("webm"));
        verify(diagnostics).get(learner,sessionId);
    }
    @Test void disabledUploadsRejectPostsButCapabilitiesRemainQueryable() {
        var disabled=new DiagnosticEvidenceService(diagnostics,sessions,repo,audio,
                scores,rollback,coordinator,false);
        assertFalse(disabled.capabilities(learner,sessionId).enabled());
        var rejected=assertThrows(ResponseStatusException.class,
                ()->disabled.submit(learner,sessionId,request,"Yesterday",null,null,recording,null));
        assertEquals(HttpStatus.FORBIDDEN,rejected.getStatusCode());
        assertEquals("UPLOAD_DISABLED",rejected.getReason());
        verify(audio,never()).store(any());
    }
    @Test void locationResolvesOnlyFromOwningSession() {
        var existing=new DiagnosticEvidenceRepository.Evidence(UUID.randomUUID(),
                sessionId,request,"Yesterday",null,null,null,Instant.now());
        var view=new EvidenceDtos.EvidenceView(existing.id(),existing.title(),null,null,
                existing.submittedAt(),null,null,"STUDENT_UPLOADED","NOT_ASSESSED");
        when(repo.list(sessionId)).thenReturn(java.util.List.of(existing));
        when(repo.view(existing)).thenReturn(view);
        assertEquals(existing.id(),service.get(learner,sessionId,existing.id()).id());
        assertEquals("EVIDENCE_NOT_FOUND",assertThrows(ResponseStatusException.class,
                ()->service.get(learner,sessionId,UUID.randomUUID())).getReason());
        verify(diagnostics,atLeastOnce()).get(learner,sessionId);
    }
    @Test void missingAudioDoesNotCreateEvidence() {
        assertEquals("MISSING_AUDIO", assertThrows(ResponseStatusException.class,
            ()->service.submit(learner,sessionId,request,"Yesterday",null,null,null,null)).getReason());
        verify(repo,never()).addEvidence(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void foreignOwnerCannotUploadOrLockAnyMedia() {
        var foreign=UUID.randomUUID();
        var refused=assertThrows(ResponseStatusException.class,
                ()->service.submit(foreign,sessionId,request,"Yesterday",null,null,recording,null));
        assertEquals(HttpStatus.NOT_FOUND,refused.getStatusCode());
        assertEquals("SESSION_NOT_FOUND",refused.getReason());
        verify(sessions).findLockedOwnedById(foreign,sessionId);
        verify(audio,never()).store(any());
        verify(repo,never()).addEvidence(any(),any(),any(),any(),any(),any(),any());
    }
    @Test void cannotUploadAfterContinue() {
        when(session.getStatus()).thenReturn(DiagnosticStatus.READY_FOR_TASK);
        var error=assertThrows(ResponseStatusException.class,
                ()->service.submit(learner,sessionId,request,"Yesterday",null,null,recording,null));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        verify(audio,never()).store(any());
    }
    @Test void committedRetryDoesNotStoreAgainEvenAfterContinue() {
        when(session.getStatus()).thenReturn(DiagnosticStatus.TASK_ASSIGNED);
        var existing=new DiagnosticEvidenceRepository.Evidence(UUID.randomUUID(),
                sessionId,request,"Yesterday",null,null,
                EvidenceRequestFingerprint.calculate("Yesterday",null,null,recording,null),Instant.now());
        var view=new EvidenceDtos.EvidenceView(existing.id(),existing.title(),null,null,
                existing.submittedAt(),null,null,"STUDENT_UPLOADED","NOT_ASSESSED");
        when(repo.byRequest(sessionId,request)).thenReturn(Optional.of(existing));
        when(repo.view(existing)).thenReturn(view);
        var result=service.submit(learner,sessionId,request,"Yesterday",null,null,recording,null);
        assertFalse(result.created());
        assertEquals(existing.id(),result.evidence().id());
        verify(audio,never()).store(any());
    }
    @Test void changedPayloadWithSameRequestIdConflictsBeforeStoringAnyFile() {
        var existing=new DiagnosticEvidenceRepository.Evidence(UUID.randomUUID(),
                sessionId,request,"Yesterday",null,null,
                EvidenceRequestFingerprint.calculate("Yesterday",null,null,recording,null),Instant.now());
        when(repo.byRequest(sessionId,request)).thenReturn(Optional.of(existing));
        var replacedAudio=new MockMultipartFile("audio","test.wav","audio/wav",
                new byte[]{'R','I','F','F',1,0,0,0,'W','A','V','E'});
        var error=assertThrows(ResponseStatusException.class,()->service.submit(
                learner,sessionId,request,"Yesterday",null,null,replacedAudio,null));
        assertEquals(HttpStatus.CONFLICT,error.getStatusCode());
        assertEquals("IDEMPOTENCY_PAYLOAD_MISMATCH",error.getReason());
        verify(audio,never()).store(any());
    }

    @Test void quotaIsEnforcedBeforeSavingAudio() {
        when(repo.count(sessionId)).thenReturn(10L);
        assertEquals("MAX_ITEMS_REACHED",assertThrows(ResponseStatusException.class,
            ()->service.submit(learner,sessionId,request,"Yesterday",null,null,recording,null))
                .getReason());
        verify(audio,never()).store(any());
    }
    @Test void acceptsOptionalScoreAndRegistersAudioRollback() {
        var pdf=new MockMultipartFile("score","score.pdf","application/pdf","%PDF-1.7\n".getBytes());
        var storedDoc=new DocumentMediaStore.Stored(UUID.randomUUID()+".pdf","pdf",9L);
        when(scores.store(pdf)).thenReturn(storedDoc);
        var created=new DiagnosticEvidenceRepository.Evidence(UUID.randomUUID(),
                sessionId,request,"Yesterday","Beatles",null,null,Instant.now());
        when(repo.byRequest(sessionId,request)).thenReturn(Optional.empty(),Optional.of(created));
        when(repo.view(created)).thenReturn(new EvidenceDtos.EvidenceView(
                created.id(),"Yesterday","Beatles",null,created.submittedAt(),
                null,null,"STUDENT_UPLOADED","NOT_ASSESSED"));
        var result=service.submit(learner,sessionId,request," Yesterday "," Beatles ",
                null,recording,pdf);
        assertTrue(result.created());
        verify(repo).addEvidence(any(),eq(sessionId),eq(request),
                eq("Yesterday"),eq("Beatles"),isNull(),anyString());
        verify(repo).addMedia(any(UUID.class),eq("SCORE"),eq(storedDoc.key()),
                eq("pdf"),eq(9L),eq("PROCESSED"));
        verify(rollback).register(anyString(),eq(request));
    }
}
