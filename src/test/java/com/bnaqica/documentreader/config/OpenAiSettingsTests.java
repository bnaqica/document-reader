package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.moderation.ModerationModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Spring AI's OpenAI settings, read from the real {@code application.yml} (plus the test overlay). Building the models
 * contacts nothing; only a call would.
 */
class OpenAiSettingsTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    ToolCallingAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class,
                    OpenAiEmbeddingAutoConfiguration.class,
                    OpenAiImageAutoConfiguration.class,
                    OpenAiAudioSpeechAutoConfiguration.class,
                    OpenAiAudioTranscriptionAutoConfiguration.class,
                    OpenAiModerationAutoConfiguration.class));

    @Test
    void onlyTheChatModelIsSetUp() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).doesNotHaveBean(EmbeddingModel.class);
            assertThat(context).doesNotHaveBean(ImageModel.class);
            assertThat(context).doesNotHaveBean(TextToSpeechModel.class);
            assertThat(context).doesNotHaveBean(TranscriptionModel.class);
            assertThat(context).doesNotHaveBean(ModerationModel.class);
        });
    }

    /** The inbox retries whole steps, so the SDK's own retries stay low. */
    @Test
    void sdkRetriesTwice() {
        runner.run(context -> assertThat(
                        context.getBean(OpenAiChatProperties.class).getMaxRetries())
                .isEqualTo(2));
    }

    @Test
    void chatModelFollowsOurModel() {
        runner.withPropertyValues("OPENAI_MODEL=model-from-env")
                .run(context -> assertThat(
                                context.getBean(OpenAiChatProperties.class).getModel())
                        .isEqualTo("model-from-env"));
    }

    /** One call can't use up the 10-minute inbox lease. */
    @Test
    void callTimesOutAfterTwoMinutes() {
        runner.run(context -> assertThat(
                        context.getBean(OpenAiChatProperties.class).getTimeout())
                .isEqualTo(Duration.ofSeconds(120)));
    }
}
