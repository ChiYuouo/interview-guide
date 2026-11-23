package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.llmprovider.service.LlmProviderConfigService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("QwenTtsService Unit Tests")
class QwenTtsServiceTest {

    private QwenTtsService ttsService;

    @BeforeEach
    void setUp() {
        ttsService = new QwenTtsService(new VoiceInterviewProperties(),
                mock(LlmProviderConfigService.class));
    }

    @Test
    @DisplayName("Should return empty array for empty text")
    void testSynthesizeEmptyText() {
        byte[] result = ttsService.synthesize(7L, "");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for null text")
    void testSynthesizeNullText() {
        byte[] result = ttsService.synthesize(7L, null);

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should return empty array for whitespace text")
    void testSynthesizeWhitespaceText() {
        byte[] result = ttsService.synthesize(7L, "   ");

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should cleanup resources on destroy")
    void testDestroy() {
        // Destroy should cleanup resources without error
        assertDoesNotThrow(() -> ttsService.destroy());
    }

    @Test
    @DisplayName("WebSocket 握手失败时应在超时内返回")
    void shouldReturnPromptlyWhenWebSocketHandshakeFails() {
        var properties = new VoiceInterviewProperties();
        properties.setTtsConnectTimeoutSeconds(1);
        var tts = properties.getQwen().getTts();
        tts.setUrl("ws://127.0.0.1:1");
        tts.setApiKey("test-api-key");
        var configs = mock(LlmProviderConfigService.class);
        when(configs.getTtsRuntimeConfig(7L)).thenReturn(tts);
        ttsService = new QwenTtsService(properties, configs);

        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertEquals(0, ttsService.synthesize(7L, "握手失败测试").length));
    }
}
