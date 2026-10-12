package com.is.aigul.service;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class GptServiceTest {

    @Test
    void cardEditPromptKeepsExistingPriceUnlessUserExplicitlyChangesIt() {
        GptService service = new GptService();

        String prompt = (String) ReflectionTestUtils.getField(service, "CARD_EDIT_SYSTEM_PROMPT");

        assertThat(prompt)
                .contains("do not compare against the catalog price")
                .contains("preserve that row's current originalPrice")
                .contains("explicitly states a different price, use APPEND")
                .contains("Never copy originalPrice, discount, finalPrice, or totalPrice")
                .contains("set originalPrice to 0 or null")
                .contains("do not include originalPrice in explicitFields");
    }

    @Test
    void buildsDeepSeekV4FlashJsonRequestInNonThinkingMode() {
        GptService service = deepSeekService(false);

        ObjectNode request = ReflectionTestUtils.invokeMethod(service, "buildRequestBody", true);
        ArrayNode messages = JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode().put("role", "user").put("content", "test"));
        ReflectionTestUtils.invokeMethod(service, "setMessages", request, messages);

        assertThat(request.path("model").asText()).isEqualTo("deepseek-v4-flash");
        assertThat(request.path("stream").asBoolean()).isFalse();
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.path("messages")).isEqualTo(messages);
        assertThat(request.has("input")).isFalse();
        assertThat(request.has("store")).isFalse();
        assertThat(request.has("reasoning")).isFalse();
        assertThat(request.has("text")).isFalse();
    }

    @Test
    void keepsOpenAiResponsesRequestAvailableForRollback() {
        GptService service = new GptService();
        ReflectionTestUtils.setField(service, "provider", "openai");
        ReflectionTestUtils.setField(service, "openAiModel", "gpt-5.4-mini");
        ReflectionTestUtils.setField(service, "openAiReasoningEffort", "low");

        ObjectNode request = ReflectionTestUtils.invokeMethod(service, "buildRequestBody", true);
        ArrayNode messages = JsonNodeFactory.instance.arrayNode();
        ReflectionTestUtils.invokeMethod(service, "setMessages", request, messages);

        assertThat(request.path("model").asText()).isEqualTo("gpt-5.4-mini");
        assertThat(request.path("store").asBoolean()).isFalse();
        assertThat(request.path("reasoning").path("effort").asText()).isEqualTo("low");
        assertThat(request.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(request.path("input")).isEqualTo(messages);
        assertThat(request.has("messages")).isFalse();
    }

    @Test
    void extractsContentFromDeepSeekChatCompletionShape() throws Exception {
        GptService service = new GptService();

        String output = service.extractChatCompletionContent("""
                {
                  "choices": [
                    {"message": {"role": "assistant", "content": "{\\"commands\\":[]}"}}
                  ]
                }
                """);

        assertThat(output).isEqualTo("{\"commands\":[]}");
    }

    @Test
    void extractsOutputTextFromResponsesApiShape() throws Exception {
        GptService service = new GptService();

        String output = service.extractResponsesOutputText("""
                {
                  "output": [
                    {
                      "type": "message",
                      "content": [
                        {"type": "output_text", "text": "{\\"commands\\":[]}"}
                      ]
                    }
                  ]
                }
                """);

        assertThat(output).isEqualTo("{\"commands\":[]}");
    }

    @Test
    void extractsConvenienceOutputTextWhenPresent() throws Exception {
        GptService service = new GptService();

        String output = service.extractResponsesOutputText("""
                {"output_text":"plain answer"}
                """);

        assertThat(output).isEqualTo("plain answer");
    }

    private GptService deepSeekService(boolean thinkingEnabled) {
        GptService service = new GptService();
        ReflectionTestUtils.setField(service, "provider", "deepseek");
        ReflectionTestUtils.setField(service, "deepSeekModel", "deepseek-v4-flash");
        ReflectionTestUtils.setField(service, "deepSeekThinkingEnabled", thinkingEnabled);
        return service;
    }
}
