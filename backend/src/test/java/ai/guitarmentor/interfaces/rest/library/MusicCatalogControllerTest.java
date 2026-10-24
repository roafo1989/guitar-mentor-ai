package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.MusicCatalogRepository;
import ai.guitarmentor.infrastructure.persistence.MusicCatalogRepository.Work;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicCatalogControllerTest {
    private final MusicCatalogRepository repo=mock(MusicCatalogRepository.class);
    private final MusicCatalogController controller=new MusicCatalogController(repo);
    private final UUID alice=UUID.randomUUID();
    private MockHttpServletRequest request(UUID id) {
        var r=new MockHttpServletRequest();
        r.setAttribute("guitarMentorActor",new CurrentActor(id,null,"LEARNER"));
        return r;
    }
    @Test void missingActorDeniedBeforeQuery() {
        var err=assertThrows(RuntimeException.class,()->controller.search(
            new MockHttpServletRequest(),"","","","title",20,0));
        assertEquals("UNAUTHENTICATED",err.getMessage());
        verifyNoInteractions(repo);
    }
    @Test void listAlwaysUsesAuthenticatedAccountAndNoStore() {
        var w=new Work("w1","Yesterday","Beatles","CANDIDATE");
        when(repo.search(alice,"Yesterday","gpx","PARSED","artist",2,0))
            .thenReturn(List.of(w,new Work("w2","Test","","UNRESOLVED")));
        var res=controller.search(request(alice),"Yesterday","GPX","PARSED","artist",1,0);
        assertEquals("no-store",res.getHeaders().getCacheControl());
        assertEquals("w1",res.getBody().items().getFirst().id());
        assertEquals(1L,res.getBody().nextOffset());
        verify(repo).search(alice,"Yesterday","gpx","PARSED","artist",2,0);
    }
    @Test void invalidInputsFailWithoutDatabaseCall() {
        var req=request(alice);
        for(int limit:new int[]{0,51}) {
            assertEquals("MUSIC_CATALOG_INVALID_QUERY",assertThrows(RuntimeException.class,
                ()->controller.search(req,"","","","title",limit,0)).getMessage());
        }
        assertEquals("MUSIC_CATALOG_INVALID_QUERY",assertThrows(RuntimeException.class,
            ()->controller.search(req,"","","","title",20,-1)).getMessage());
        assertEquals("MUSIC_CATALOG_INVALID_QUERY",assertThrows(RuntimeException.class,
            ()->controller.search(req,"","","","random",20,0)).getMessage());
        assertEquals("MUSIC_CATALOG_INVALID_QUERY",assertThrows(RuntimeException.class,
            ()->controller.search(req,"","","PENDING;DROP","title",20,0)).getMessage());
        verifyNoInteractions(repo);
    }
    @Test void detailUsesOwnerAndHidesOtherAccount() {
        var w=new Work("one","Yesterday","Beatles","CANDIDATE");
        when(repo.work(alice,"one")).thenReturn(Optional.of(w));
        when(repo.arrangements(alice,"one")).thenReturn(List.of());
        assertEquals("one",controller.detail(request(alice),"one").getBody().work().id());
        assertEquals("MUSIC_CATALOG_NOT_FOUND",assertThrows(RuntimeException.class,
            ()->controller.detail(request(UUID.randomUUID()),"one")).getMessage());
        verify(repo).work(alice,"one");
    }
}
