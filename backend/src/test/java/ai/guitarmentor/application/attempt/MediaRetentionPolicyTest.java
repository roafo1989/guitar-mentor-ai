package ai.guitarmentor.application.attempt;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.guitarmentor.infrastructure.persistence.SystemSettingRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

class MediaRetentionPolicyTest {
    private final SystemSettingRepository settings=mock(SystemSettingRepository.class);
    private final MediaRetentionPolicy reader=new MediaRetentionPolicy(settings);
    private void settings(String after,String max,String interval) {
        when(settings.snapshot()).thenReturn(List.of(
            new SystemSettingRepository.Setting("media.retention_after_processing_hours",after),
            new SystemSettingRepository.Setting("media.max_unprocessed_age_hours",max),
            new SystemSettingRepository.Setting("media.cleanup_interval_minutes",interval)));
    }
    @Test void defaultPolicy() {
        settings("48","48","60");
        var policy=reader.read();
        assertEquals(48,policy.afterFinal().toHours());
        assertEquals(48,policy.maxUnprocessed().toHours());
        assertEquals(60,policy.interval().toMinutes());
    }
    @Test void refusePartialInvalidOrOverflowPolicy() {
        for (String invalid:List.of("0","-1","abc","9223372036854775807")) {
            settings(invalid,"48","60");
            assertEquals("MEDIA_CLEANUP_CONFIG_INVALID",
                assertThrows(IllegalArgumentException.class,reader::read).getMessage());
        }
    }
}
