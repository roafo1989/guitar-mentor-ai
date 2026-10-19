package ai.guitarmentor.application.attempt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MediaCleanupServiceTest {
    @Test void missingFileIsConfirmedAndHistoryRemainsInDbLayer() throws Exception {
        var db=mock(MediaCleanupTransactions.class);
        var policy=mock(MediaRetentionPolicy.class);
        var media=mock(AttemptMediaStore.class);
        var guard=new MediaUseCoordinator();
        var service=new MediaCleanupService(db,policy,media,guard);
        var id=UUID.randomUUID();
        var now=Instant.parse("2026-10-19T12:00:00Z");
        var p=new MediaRetentionPolicy.Policy(Duration.ofHours(48),
            Duration.ofHours(48),Duration.ofHours(1));
        when(db.claim(now,p)).thenReturn(List.of(new MediaCleanupTransactions.Candidate(id,
            "00000000-0000-0000-0000-000000000001.wav")));
        when(media.delete(anyString())).thenReturn(AttemptMediaStore.DeleteOutcome.ALREADY_ABSENT);
        service.runCycle(now,p);
        verify(db).deleted(eq(id),any(Instant.class),eq(true));
        verify(db,never()).failed(any());
    }

    @Test void invalidPolicyNeverTouchesStorageOrDatabase() {
        var db=mock(MediaCleanupTransactions.class);
        var policy=mock(MediaRetentionPolicy.class);
        var media=mock(AttemptMediaStore.class);
        when(policy.read()).thenThrow(new IllegalArgumentException("MEDIA_CLEANUP_CONFIG_INVALID"));
        var service=new MediaCleanupService(db,policy,media,new MediaUseCoordinator());
        service.tick();
        verifyNoInteractions(db,media);
    }

    @Test void concurrentWorkerGuardPreventsScheduledDeletion() throws Exception {
        var db=mock(MediaCleanupTransactions.class);
        var policy=mock(MediaRetentionPolicy.class);
        var media=mock(AttemptMediaStore.class);
        var guard=new MediaUseCoordinator();
        var service=new MediaCleanupService(db,policy,media,guard);
        when(policy.read()).thenReturn(new MediaRetentionPolicy.Policy(
            Duration.ofHours(48),Duration.ofHours(48),Duration.ofMinutes(60)));
        var acquired=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var future=worker.submit(()->{
                guard.acquire();
                try {
                    acquired.countDown();
                    release.await(5,java.util.concurrent.TimeUnit.SECONDS);
                } finally { guard.release(); }
                return true;
            });
            assertTrue(acquired.await(5,java.util.concurrent.TimeUnit.SECONDS));
            service.tick();
            verifyNoInteractions(db,media);
            release.countDown();
            assertTrue(future.get(5,java.util.concurrent.TimeUnit.SECONDS));
            service.tick();
            verify(db).claim(any(Instant.class),any(MediaRetentionPolicy.Policy.class));
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void ioErrorCannotRecordSuccessfulDeletion() throws Exception {
        var db=mock(MediaCleanupTransactions.class);
        var service=new MediaCleanupService(db,mock(MediaRetentionPolicy.class),
            mock(AttemptMediaStore.class),new MediaUseCoordinator());
        var id=UUID.randomUUID();
        var now=Instant.now();
        var p=new MediaRetentionPolicy.Policy(Duration.ofHours(48),
            Duration.ofHours(48),Duration.ofHours(1));
        when(db.claim(now,p)).thenReturn(List.of(new MediaCleanupTransactions.Candidate(id,"x.wav")));
        var mediaField=MediaCleanupService.class.getDeclaredField("store");
        mediaField.setAccessible(true);
        var media=(AttemptMediaStore)mediaField.get(service);
        when(media.delete("x.wav")).thenThrow(new IOException("DELETE_IO_ERROR"));
        service.runCycle(now,p);
        verify(db).failed(id);
        verify(db,never()).deleted(any(),any(),anyBoolean());
    }
}
