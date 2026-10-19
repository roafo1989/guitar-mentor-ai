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
