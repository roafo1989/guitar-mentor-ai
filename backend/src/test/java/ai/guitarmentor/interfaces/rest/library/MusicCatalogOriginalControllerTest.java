package ai.guitarmentor.interfaces.rest.library;

import ai.guitarmentor.infrastructure.persistence.MusicCatalogRepository;
import ai.guitarmentor.infrastructure.security.CurrentActor;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MusicCatalogOriginalControllerTest {
    @Test void disabledStorageAndGuestCannotAttach() {
        var repo=mock(MusicCatalogRepository.class);
        var controller=new MusicCatalogOriginalController(repo,false);
        var request=new MockHttpServletRequest();
        assertEquals("UNAUTHENTICATED",assertThrows(RuntimeException.class,()->controller.bind(request,"f",Map.of())).getMessage());
        request.setAttribute("guitarMentorActor",new CurrentActor(UUID.randomUUID(),null,"LEARNER"));
        assertEquals("LIBRARY_DISABLED",assertThrows(RuntimeException.class,()->controller.bind(request,"f",Map.of())).getMessage());
        verifyNoInteractions(repo);
    }
}
