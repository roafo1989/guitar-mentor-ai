package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository.Material;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LibraryMaterialDeleteControllerTest {
    private final LibraryMaterialRepository repository=mock(LibraryMaterialRepository.class);
    private final LibraryMaterialController controller=
        new LibraryMaterialController(repository,"test-only-hmac-secret-32-bytes-long",true);
    private final UUID alice=UUID.randomUUID();
    private final UUID id=UUID.randomUUID();

    private MockHttpServletRequest request(UUID actorId) {
        var req=new MockHttpServletRequest();
        req.setAttribute("guitarMentorActor",new CurrentActor(actorId,null,"LEARNER"));
        return req;
    }

    private Material material(String state,long revision) {
        var date=OffsetDateTime.parse("2026-10-25T00:00:00Z");
        return new Material(id,"Test","COMPOSITION",null,state,revision,date,date);
    }

    @Test void ownerDeleteQueuesAndImmediatelyReturns202() {
        when(repository.findIncludingDeleting(alice,id)).thenReturn(Optional.of(material("READY",4)));
        when(repository.markDeleting(alice,id,4)).thenReturn(1);
        var result=controller.delete(request(alice),id,"\"4\"");
        assertEquals(202,result.getStatusCode().value());
        assertEquals("DELETING",result.getBody().state());
        assertEquals("no-store",result.getHeaders().getCacheControl());
        verify(repository).markDeleting(alice,id,4);
        verify(repository).queueDeletion(id);
    }

    @Test void replayOnTombstoneIsIdempotentAndNoNewJob() {
        when(repository.findIncludingDeleting(alice,id))
            .thenReturn(Optional.of(material("DELETING",5)));
        assertEquals(202,controller.delete(request(alice),id,"\"4\"").getStatusCode().value());
        verify(repository,never()).markDeleting(any(),any(),anyLong());
        verify(repository,never()).queueDeletion(any());
    }

    @Test void staleVersionNeverQueues() {
        when(repository.findIncludingDeleting(alice,id)).thenReturn(Optional.of(material("READY",5)));
        assertEquals("LIBRARY_REVISION_CONFLICT",assertThrows(RuntimeException.class,
            ()->controller.delete(request(alice),id,"\"4\"")).getMessage());
        verify(repository,never()).queueDeletion(any());
    }

    @Test void crossAccountAndMissingAuthDenied() {
        when(repository.findIncludingDeleting(alice,id)).thenReturn(Optional.empty());
        assertEquals("LIBRARY_MATERIAL_NOT_FOUND",assertThrows(RuntimeException.class,
            ()->controller.delete(request(alice),id,"\"4\"")).getMessage());
        assertEquals("UNAUTHENTICATED",assertThrows(RuntimeException.class,
            ()->controller.delete(new MockHttpServletRequest(),id,"\"4\"")).getMessage());
        verify(repository,never()).queueDeletion(any());
    }

    @Test void ifMatchRequired() {
        assertEquals("LIBRARY_PRECONDITION_REQUIRED",assertThrows(RuntimeException.class,
            ()->controller.delete(request(alice),id,null)).getMessage());
        verifyNoInteractions(repository);
    }
}
