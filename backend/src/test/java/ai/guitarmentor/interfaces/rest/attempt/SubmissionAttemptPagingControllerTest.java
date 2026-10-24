package ai.guitarmentor.interfaces.rest.attempt;

import ai.guitarmentor.application.attempt.SubmissionAttemptService;
import ai.guitarmentor.interfaces.rest.attempt.AttemptDtos.AttemptPage;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubmissionAttemptPagingControllerTest {
    private final SubmissionAttemptService service = mock(SubmissionAttemptService.class);
    private final SubmissionAttemptController controller = new SubmissionAttemptController(service);
    private final UUID learner = UUID.randomUUID(), session = UUID.randomUUID(), task = UUID.randomUUID();

    @Test
    void invalidParametersAreRejectedWithoutDatabaseReads() {
        for (String bad : List.of("", "0", "51", "-1", "2.5", "abc", "2147483648", " 2")) {
            var q = new LinkedMultiValueMap<String,String>();
            q.add("limit",bad);
            assertEquals(HttpStatus.BAD_REQUEST,controller.page(learner,session,task,q).getStatusCode());
        }
        var duplicate = new LinkedMultiValueMap<String,String>();
        duplicate.add("limit","2");
        duplicate.add("limit","3");
        assertEquals(HttpStatus.BAD_REQUEST,controller.page(learner,session,task,duplicate).getStatusCode());
        for (String bad : List.of("", "0", "text", "2147483648")) {
            var q = new LinkedMultiValueMap<String,String>();
            q.add("beforeNumber",bad);
            assertEquals(HttpStatus.BAD_REQUEST,controller.page(learner,session,task,q).getStatusCode());
        }
        verifyNoInteractions(service);
    }

    @Test
    void defaultsAndSafeResponses() {
        when(service.page(learner,session,task,20,null))
                .thenReturn(new AttemptPage(List.of(),null,false));
        var empty = controller.page(learner,session,task,new LinkedMultiValueMap<>());
        assertEquals(HttpStatus.OK, empty.getStatusCode());
        assertTrue(empty.getHeaders().getCacheControl().contains("no-store"));
        assertEquals(HttpStatus.BAD_REQUEST,
                controller.detail(learner,session,task,"not-a-uuid").getStatusCode());
        when(service.page(learner,session,task,1,null)).thenThrow(new EntityNotFoundException());
        var query = new LinkedMultiValueMap<String,String>();
        query.add("limit","1");
        assertEquals(HttpStatus.NOT_FOUND,controller.page(learner,session,task,query).getStatusCode());
        when(service.page(learner,session,task,2,null)).thenThrow(new IllegalStateException("sensitive path"));
        query.set("limit","2");
        var failure = controller.page(learner,session,task,query);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failure.getStatusCode());
        assertEquals(java.util.Map.of("code","ATTEMPT_READ_FAILED"),failure.getBody());
    }

    @Test
    void mvcTransportNeverLeaksReadFailuresOnPageOrDetail() throws Exception {
        // Exercise actual Spring MVC handler mapping, serialization, and response headers,
        // rather than invoking controller methods directly.
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        String base = "/api/v1/learners/" + learner + "/diagnostic-sessions/" + session
                + "/tasks/" + task + "/attempts";
        when(service.page(learner,session,task,20,null))
                .thenThrow(new IllegalStateException("secret jdbc credentials: /mnt/media/key.wav"));
        var attemptId = UUID.randomUUID();
        when(service.detail(learner,session,task,attemptId))
                .thenThrow(new IllegalStateException("private media path: /var/private/audio"));

        for (String uri : List.of(base + "/page", base + "/" + attemptId)) {
            var response = mvc.perform(get(uri))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                    .andExpect(content().json("{\"code\":\"ATTEMPT_READ_FAILED\"}"))
                    .andReturn().getResponse();
            assertEquals("{\"code\":\"ATTEMPT_READ_FAILED\"}",response.getContentAsString());
            assertFalse(response.getContentAsString().contains("media"));
        }
    }

    @Test
    void capabilitiesChecksOwnershipWithoutReadingHistory() {
        var result = controller.capabilities(learner,session,task);
        assertEquals(HttpStatus.OK,result.getStatusCode());
        assertTrue(result.getHeaders().getCacheControl().contains("no-store"));
        verify(service).verifyOwnership(learner,session,task);
        verify(service).isEnabled();
        verifyNoMoreInteractions(service);
    }
}
