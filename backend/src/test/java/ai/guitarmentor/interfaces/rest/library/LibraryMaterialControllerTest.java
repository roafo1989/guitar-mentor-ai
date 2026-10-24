package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository;
import ai.guitarmentor.infrastructure.persistence.LibraryMaterialRepository.Material;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LibraryMaterialControllerTest {
    private final LibraryMaterialRepository repository=mock(LibraryMaterialRepository.class);
    private final LibraryMaterialController controller=new LibraryMaterialController(repository);
    private final UUID owner=UUID.randomUUID();
    private MockHttpServletRequest request() {
        var request=new MockHttpServletRequest();
        request.setAttribute("guitarMentorActor",new CurrentActor(owner,null,"LEARNER"));
        return request;
    }
    private Material material(UUID id,long revision) {
        return new Material(id,"Blues","ETUDE","","READY",revision,
                OffsetDateTime.parse("2026-10-24T10:00:00Z"),
                OffsetDateTime.parse("2026-10-24T10:00:00Z"));
    }
    @Test void missingActorIsRejected() {
        var error=assertThrows(RuntimeException.class,
                ()->controller.list(new MockHttpServletRequest(),20,null));
        assertEquals("UNAUTHENTICATED",error.getMessage());
    }
    @Test void ownerAlwaysPassedToRepository() {
        UUID id=UUID.randomUUID();
        when(repository.find(owner,id)).thenReturn(Optional.of(material(id,0)));
        assertEquals(id,controller.get(request(),id).getBody().id());
        verify(repository).find(owner,id);
    }
    @Test void invalidCursorRejectedBeforeDatabaseQuery() {
        var error=assertThrows(RuntimeException.class,()->controller.list(request(),20,"%%%"));
        assertEquals("INVALID_CURSOR",error.getMessage());
        verifyNoInteractions(repository);
    }
    @Test void conflictReturnsPreconditionFailure() {
        UUID id=UUID.randomUUID();
        when(repository.find(owner,id)).thenReturn(Optional.of(material(id,2)));
        var error=assertThrows(RuntimeException.class,()->controller.edit(request(),id,
                "\"1\"",new LibraryMaterialController.Edit("Blues","ETUDE","")));
        assertEquals("REVISION_CONFLICT",error.getMessage());
        verify(repository).update(owner,id,1,"Blues","ETUDE","");
    }
    @Test void paginationReturnsStableCursor() {
        var first=material(UUID.randomUUID(),0);
        var second=material(UUID.randomUUID(),0);
        when(repository.page(owner,null,null,2)).thenReturn(List.of(first,second));
        var page=controller.list(request(),1,null).getBody();
        assertEquals(List.of(first),page.items());
        assertNotNull(page.nextCursor());
    }
}
