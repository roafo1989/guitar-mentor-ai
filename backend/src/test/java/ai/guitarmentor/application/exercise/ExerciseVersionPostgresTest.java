package ai.guitarmentor.application.exercise;

import static org.junit.jupiter.api.Assertions.*;

import ai.guitarmentor.interfaces.rest.exercise.ExerciseController;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Isolated rows and test-classpath assets: all four resource kinds pinned to the row version. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "GUITAR_MENTOR_TEST_POSTGRES", matches = "true")
class ExerciseVersionPostgresTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ExerciseCatalogService catalog;
    @Autowired ExerciseVersionResourceService resources;
    @Autowired ExerciseController controller;
    private MockMvc mvc;

    @BeforeEach
    void mvcSetup() {
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void twoVersionsExposeDistinctBytesAndGetHeadContracts() throws Exception {
        String code = "QA120-" + UUID.randomUUID().toString().substring(0, 8);
        UUID v1 = UUID.randomUUID(), v2 = UUID.randomUUID(), missing = UUID.randomUUID();
        try {
            insert(code, "v1", false, v1, "v1", true);
            insert(code, "v2", true, v2, "v2", true);
            insert(code, "v1-missing-pdf", false, missing, "v1", false);
            assertEquals("v1", catalog.findVersion(code, "v1").version());
            assertEquals("v2", catalog.find(code).version());
            assertEquals("v2", catalog.findVersion(code, "v2").version());
            for (var suffix : Map.of("score", "application/vnd.recordare.musicxml+xml",
                    "events", "application/json",
                    "preview/audio", "audio/mpeg",
                    "preview/pdf", "application/pdf").entrySet()) {
                String tail = suffix.getKey(), mime = suffix.getValue();
                String oldUrl = "/api/v1/exercises/" + code + "/versions/v1/" + tail;
                String currentUrl = "/api/v1/exercises/" + code + "/versions/v2/" + tail;
                var oldGet = mvc.perform(get(oldUrl))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Content-Type", mime))
                        .andExpect(header().string("Cache-Control", "no-cache"))
                        .andReturn().getResponse();
                var newGet = mvc.perform(get(currentUrl))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Content-Type", mime))
                        .andExpect(header().string("Cache-Control", "no-cache"))
                        .andReturn().getResponse();
                assertTrue(oldGet.getContentAsByteArray().length > 0, tail);
                assertTrue(newGet.getContentAsByteArray().length > 0, tail);
                assertFalse(java.util.Arrays.equals(oldGet.getContentAsByteArray(),
                        newGet.getContentAsByteArray()), "Version leakage for " + tail);
                for (String url : java.util.List.of(oldUrl, currentUrl)) {
                    var head = mvc.perform(head(url))
                            .andExpect(status().isOk())
                            .andExpect(header().string("Content-Type", mime))
                            .andExpect(header().string("Cache-Control", "no-cache"))
                            .andReturn().getResponse();
                    assertEquals(0, head.getContentAsByteArray().length, "HEAD body " + url);
                }
            }
            var assigned = catalog.findVersion(code, "v1");
            assertEquals("/api/v1/exercises/" + code + "/versions/v1/preview/pdf",
                    assigned.previewPdfUrl());
            assertEquals("/api/v1/exercises/" + code + "/versions/v1/preview/audio",
                    assigned.previewAudioUrl());
            assertNull(catalog.findVersion(code, "v1-missing-pdf").previewPdfUrl());

            String missingUrl = "/api/v1/exercises/" + code + "/versions/v1-missing-pdf/preview/pdf";
            mvc.perform(get(missingUrl))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EXERCISE_RESOURCE_NOT_FOUND"));
            mvc.perform(head(missingUrl))
                    .andExpect(status().isNotFound())
                    .andExpect(header().string("Cache-Control", "no-cache"))
                    .andExpect(content().bytes(new byte[0]));
            mvc.perform(get("/api/v1/exercises/" + code + "/versions/unknown"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("EXERCISE_NOT_FOUND"));
            mvc.perform(get("/api/v1/exercises/" + code + "/versions/bad%25version"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_EXERCISE_REFERENCE"));
            assertEquals(v1, jdbc.queryForObject("SELECT id FROM exercises WHERE code=? AND version='v1'",
                    UUID.class, code));
            assertEquals(v2, jdbc.queryForObject("SELECT id FROM exercises WHERE code=? AND version='v2'",
                    UUID.class, code));
        } finally {
            jdbc.update("DELETE FROM exercises WHERE id IN (?,?,?)", v1, v2, missing);
        }
    }

    @Test
    void databaseRejectsDuplicateCodeVersionAndSecondCurrentWithoutMutatingCatalog() {
        String code = "QA120-" + UUID.randomUUID().toString().substring(0, 8);
        UUID old = UUID.randomUUID(), current = UUID.randomUUID();
        try {
            insert(code, "v1", false, old, "v1", true);
            insert(code, "v2", true, current, "v2", true);
            assertThrows(DataIntegrityViolationException.class,
                    () -> insert(code, "v1", false, UUID.randomUUID(), "v2", true));
            assertThrows(DataIntegrityViolationException.class,
                    () -> insert(code, "v3", true, UUID.randomUUID(), "v2", true));
            assertEquals(2L, jdbc.queryForObject(
                    "SELECT count(*) FROM exercises WHERE code=?", Long.class, code));
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT count(*) FROM exercises WHERE code=? AND is_current=true", Long.class, code));
            assertEquals(old, jdbc.queryForObject(
                    "SELECT id FROM exercises WHERE code=? AND version='v1'", UUID.class, code));
        } finally {
            jdbc.update("DELETE FROM exercises WHERE id IN (?,?)", old, current);
        }
    }

    private void insert(String code, String version, boolean current, UUID id,
            String assetVersion, boolean hasPdf) {
        String root = "exercises/qa120/" + assetVersion + "/";
        jdbc.update("INSERT INTO exercises (id,code,version,is_current,title,"
                        + "instrument_code,technique_code,style_code,difficulty,bpm,measures,"
                        + "meter,author,license,review_status,description,score_resource,events_resource,"
                        + "preview_audio_resource,preview_pdf_resource)"
                        + " SELECT ?,?,?,?,title,instrument_code,technique_code,style_code,"
                        + "difficulty,bpm,measures,meter,author,license,review_status,description,?,?,?,?"
                        + " FROM exercises WHERE code='GM-EB01' AND version='0.8B'",
                id, code, version, current, root + "score.musicxml", root + "events.json",
                root + "preview.mp3", hasPdf ? root + "preview.pdf" : null);
    }
}
