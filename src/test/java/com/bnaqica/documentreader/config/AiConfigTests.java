package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.service.OpenAiCallService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** The vision client is built from the chat model and the configured OpenAI settings. */
class AiConfigTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PropertiesConfig.class, AiConfig.class)
            .withBean(DocumentStorageClient.class, () -> mock(DocumentStorageClient.class))
            .withBean(OpenAiCallService.class, () -> mock(OpenAiCallService.class))
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

    @EnableConfigurationProperties(DocumentReaderProperties.class)
    static class PropertiesConfig {}

    @Test
    void buildsTheVisionClient() {
        runner.withBean(ChatModel.class, StubChatModel::new)
                .run(context ->
                        assertThat(context).getBean(VisionClient.class).isInstanceOf(SpringAiVisionClient.class));
    }

    @Test
    void usesTheConfiguredModel() {
        StubChatModel chatModel = new StubChatModel();
        chatModel.answer(SpringAiVisionClientTests.CLASSIFICATION);
        runner.withBean(ChatModel.class, () -> chatModel)
                .withPropertyValues("OPENAI_MODEL=model-from-env")
                .run(context -> {
                    context.getBean(VisionClient.class)
                            .classify(SpringAiVisionClientTests.someContext(), List.of(), List.of());

                    assertThat(SpringAiVisionClientTests.options(chatModel.lastPrompt())
                                    .getModel())
                            .isEqualTo("model-from-env");
                });
    }
}
