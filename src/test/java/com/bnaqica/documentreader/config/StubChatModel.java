package com.bnaqica.documentreader.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

/** Stands in for OpenAI: records every prompt and answers with queued responses, or throws. */
class StubChatModel implements ChatModel {

    final List<Prompt> prompts = new ArrayList<>();
    private final Deque<Supplier<ChatResponse>> answers = new ArrayDeque<>();

    /** Queues an answer with the given text and token usage. */
    StubChatModel answer(String text, int promptTokens, int completionTokens) {
        answers.add(() -> new ChatResponse(
                List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build()));
        return this;
    }

    StubChatModel answer(String text) {
        return answer(text, 100, 10);
    }

    /** Queues an answer whose response carries no usage, as Spring AI reports when OpenAI sends none. */
    StubChatModel answerWithoutUsage(String text) {
        answers.add(() -> new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return this;
    }

    /** Queues a response with no generations, as a refusal or an empty answer can come back. */
    StubChatModel answerNothing() {
        answers.add(() -> new ChatResponse(List.of()));
        return this;
    }

    StubChatModel fail(RuntimeException failure) {
        answers.add(() -> {
            throw failure;
        });
        return this;
    }

    Prompt lastPrompt() {
        return prompts.getLast();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (answers.isEmpty()) {
            throw new IllegalStateException("No answer queued");
        }
        return answers.poll().get();
    }
}
