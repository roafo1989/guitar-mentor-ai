package ai.guitarmentor.interfaces.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SystemControllerTest {

    @Test
    void pingReportsReadyBootstrap() {
        var response = new SystemController().ping();
        assertEquals("ok", response.get("status"));
        assertEquals("guitar-mentor-ai", response.get("service"));
    }
}
