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
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LibraryMaterialControllerTest {
    private final LibraryMaterialRepository repository=mock(LibraryMaterialRepository.class);
    private final LibraryMaterialController controller=new LibraryMaterialController(repository,
            "test-only-hmac-secret-32-bytes-long",true);
    private final UUID owner=UUID.randomUUID();
    private MockHttpServletRequest request() {
        var req=new MockHttpServletRequest();
        req.setAttribute("guitarMentorActor",new CurrentActor(owner,null,"LEARNER"));
        return req;
    }
    private Material material(UUID id,long revision) {
        return new Material(id,"Blues","ETUDE",null,"READY",revision,
                OffsetDateTime.parse("2026-10-24T10:00:00Z"),
                OffsetDateTime.parse("2026-10-24T10:00:00Z"));
    }
    @Test void missingActorIsRejected() {
        var e=assertThrows(RuntimeException.class,
                ()->controller.list(new MockHttpServletRequest(),20,null));
        assertEquals("UNAUTHENTICATED",e.getMessage());
    }
    @Test void ownerAlwaysPassedToRepository() {
        UUID id=UUID.randomUUID();
        when(repository.find(owner,id)).thenReturn(Optional.of(material(id,1)));
        assertEquals(id,controller.get(request(),id).getBody().id());
        assertEquals("\"1\"",controller.get(request(),id).getHeaders().getETag());
        verify(repository,atLeastOnce()).find(owner,id);
        verify(repository,atLeastOnce()).assets(owner,id);
    }
    @Test void invalidCursorRejectedBeforeDatabaseQuery() {
        var e=assertThrows(RuntimeException.class,()->controller.list(request(),20,"%%%"));
        assertEquals("LIBRARY_INVALID_CURSOR",e.getMessage());
        verifyNoInteractions(repository);
    }
    @Test void conflictReturnsPreconditionFailure() throws Exception {
        UUID id=UUID.randomUUID();
        when(repository.find(owner,id)).thenReturn(Optional.of(material(id,2)));
        var e=assertThrows(RuntimeException.class,()->controller.edit(request(),id,
                "\"1\"",java.util.Map.of("title","Blues")));
        assertEquals("LIBRARY_REVISION_CONFLICT",e.getMessage());
        verify(repository).update(owner,id,1,"Blues","ETUDE",null);
    }
    @Test void paginationReturnsOwnerSignedCursor() {
        var a=material(UUID.randomUUID(),1);
        var b=material(UUID.randomUUID(),1);
        when(repository.page(owner,null,null,2)).thenReturn(List.of(a,b));
        var page=controller.list(request(),1,null).getBody();
        assertEquals(1,page.items().size());
        assertNotNull(page.nextCursor());
        when(repository.page(owner,a.createdAt(),a.id(),2)).thenReturn(List.of(b));
        assertEquals(1,controller.list(request(),1,page.nextCursor()).getBody().items().size());
        var other=new MockHttpServletRequest();
        other.setAttribute("guitarMentorActor",new CurrentActor(UUID.randomUUID(),null,"OWNER"));
        assertEquals("LIBRARY_INVALID_CURSOR",assertThrows(RuntimeException.class,
                ()->controller.list(other,1,page.nextCursor())).getMessage());
    }
    @Test void unknownPatchFieldRejected() throws Exception {
        UUID id=UUID.randomUUID();
        assertEquals("LIBRARY_INVALID_METADATA",assertThrows(RuntimeException.class,
                ()->controller.edit(request(),id,"\"1\"",java.util.Map.of("ownerId","evil"))).getMessage());
        verifyNoInteractions(repository);
    }
}
