package io.github.ctxinf.cameraagent.legacy.mqtt;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class MqttCommandValidationTest {
    @Test public void acceptsBooleanAndIntegerOrSelectString() {
        Map<String, Object> command = new HashMap<String, Object>();
        command.put("http_enabled", Boolean.FALSE);
        command.put("rotation", Long.valueOf(90));
        command.put("screen_brightness", "50");
        assertEquals(Boolean.FALSE, MqttStatusPublisher.bool(command, "http_enabled"));
        assertEquals(Integer.valueOf(90), MqttStatusPublisher.integer(command, "rotation", 0, 270));
        assertEquals(Integer.valueOf(50), MqttStatusPublisher.integer(command, "screen_brightness", 0, 100));
        assertNull(MqttStatusPublisher.bool(command, "mcp_enabled"));
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsStringBoolean() {
        Map<String, Object> command = new HashMap<String, Object>();
        command.put("http_enabled", "false");
        MqttStatusPublisher.bool(command, "http_enabled");
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsOutOfRangeBrightness() {
        Map<String, Object> command = new HashMap<String, Object>();
        command.put("screen_brightness", Long.valueOf(101));
        MqttStatusPublisher.integer(command, "screen_brightness", 0, 100);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsFractionalBrightness() {
        Map<String, Object> command = new HashMap<String, Object>();
        command.put("screen_brightness", Double.valueOf(50.5));
        MqttStatusPublisher.integer(command, "screen_brightness", 0, 100);
    }
}
