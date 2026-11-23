package com.bnaqica.documentreader.config;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.service.OpenAiCallService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vision client over Spring AI's OpenAI chat model. Takes the application's Jackson 3 {@link JsonMapper}: the
 * OpenAI SDK brings Jackson 2 too, which must never be used here.
 */
@Configuration
public class AiConfig {

    @Bean
    VisionClient visionClient(
            ChatModel chatModel,
            DocumentStorageClient storage,
            OpenAiCallService callLog,
            JsonMapper jsonMapper,
            DocumentReaderProperties properties) {
        return new SpringAiVisionClient(chatModel, storage, callLog, jsonMapper, properties.openAi());
    }
}
