package com.bnaqica.documentreader;

import com.bnaqica.documentreader.FixtureGenerator.Marker;
import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stands in for OpenAI across the whole pipeline, behind the real vision client. It tells each call's purpose from
 * its prompt's fixed wording, and each page's document from the colour band {@link FixtureGenerator} draws across the
 * top of every fixture page. Every answer has the JSON shape the purpose's answer schema asks for. A prompt it doesn't
 * recognise throws, so a template edit fails loudly here instead of silently.
 */
public class PipelineChatModel implements ChatModel {

    /** A sentence only that purpose's template contains. */
    private static final Map<OpenAiCallPurpose, String> WORDING = new LinkedHashMap<>();

    static {
        WORDING.put(OpenAiCallPurpose.SEPARATE, "Find where each document starts.");
        WORDING.put(OpenAiCallPurpose.CLASSIFY, "Decide what type of document it is.");
        WORDING.put(OpenAiCallPurpose.TIEBREAK, "Earlier readings were split between the document types below.");
        WORDING.put(OpenAiCallPurpose.CONFIRM_TYPE, "which was read as a new kind of document");
        WORDING.put(OpenAiCallPurpose.DRAFT_SCHEMA, "Write the JSON Schema for the data");
        WORDING.put(OpenAiCallPurpose.EXTRACT, "Read the data the schema below asks for");
        WORDING.put(OpenAiCallPurpose.CONSOLIDATE, "Several readings of the same document gave the results below.");
        WORDING.put(OpenAiCallPurpose.FINAL, "Below is a draft of its data and summary");
    }

    private static final String SUMMARY_PREFIX = "Synthetic ";

    /** Thrown by the crash hook. An {@link Error}, as a dying instance's failure would be, not a step failure. */
    public static class SimulatedCrash extends Error {
        SimulatedCrash(OpenAiCallPurpose purpose) {
            super("Simulated crash during " + purpose);
        }
    }

    private final JsonMapper jsonMapper;
    private final AtomicReference<OpenAiCallPurpose> crashOn = new AtomicReference<>();
    private volatile CountDownLatch crashes = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();

    public PipelineChatModel(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    /** Makes the next call for this purpose throw {@link SimulatedCrash}; later calls answer as usual. */
    public void crashOn(OpenAiCallPurpose purpose) {
        crashes = new CountDownLatch(1);
        crashOn.set(purpose);
    }

    /** Reaches zero once the armed crash has happened. */
    public CountDownLatch crashes() {
        return crashes;
    }

    /** How many calls this model answered; an instance that did no work answered none. */
    public int calls() {
        return calls.get();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        UserMessage user = prompt.getUserMessage();
        String text = user.getText();
        OpenAiCallPurpose purpose = purposeOf(text);
        if (crashOn.compareAndSet(purpose, null)) {
            crashes.countDown();
            throw new SimulatedCrash(purpose);
        }
        List<Marker> pages =
                user.getMedia().stream().map(PipelineChatModel::markerOf).toList();
        JsonNode answer = switch (purpose) {
            case SEPARATE -> separation(pages);
            case CLASSIFY, TIEBREAK -> classification(pages.getFirst());
            case CONFIRM_TYPE -> object().put("matchesExisting", false).putNull("existingName");
            case DRAFT_SCHEMA -> throw new IllegalStateException("The pipeline tests never create a type");
            case EXTRACT, FINAL -> extraction(schemaIn(text), pages.getFirst().typeName);
            case CONSOLIDATE ->
                extraction(schemaIn(text), typeInReadings(text)).set("disagreements", jsonMapper.createArrayNode());
        };
        calls.incrementAndGet();
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(jsonMapper.writeValueAsString(answer)))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 10)).build());
    }

    private static OpenAiCallPurpose purposeOf(String text) {
        return WORDING.entrySet().stream()
                .filter(entry -> text.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown prompt: no template's wording matches"));
    }

    /** Page 1, then every page whose marker differs from the page before it, numbered from the first image. */
    private ObjectNode separation(List<Marker> pages) {
        List<Integer> starts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            if (i == 0 || pages.get(i) != pages.get(i - 1)) {
                starts.add(i + 1);
                labels.add(pages.get(i).typeName);
            }
        }
        ObjectNode answer = object();
        starts.forEach(answer.putArray("startPages")::add);
        labels.forEach(answer.putArray("labels")::add);
        return answer;
    }

    private ObjectNode classification(Marker marker) {
        return object().put("documentType", marker.typeName)
                .put("confidence", 0.9)
                .put("reasoning", "Synthetic fixture page");
    }

    /**
     * Data with every property null except the first plain string one, which names the type. The seeded types allow
     * null for every top-level property, so this is valid against the type's schema.
     */
    private ObjectNode extraction(JsonNode schema, String typeName) {
        ObjectNode data = object();
        boolean filled = false;
        for (var property : schema.get("properties").properties()) {
            JsonNode definition = property.getValue();
            if (!filled && allowsString(definition) && !definition.has("format") && !definition.has("enum")) {
                data.put(property.getKey(), "Sample " + typeName);
                filled = true;
            } else {
                data.putNull(property.getKey());
            }
        }
        ObjectNode answer = object();
        answer.set("data", data);
        answer.put("summary", SUMMARY_PREFIX + typeName);
        return answer;
    }

    private static boolean allowsString(JsonNode definition) {
        JsonNode type = definition.get("type");
        if (type == null) {
            return false;
        }
        if (type.isString()) {
            return type.asString().equals("string");
        }
        for (JsonNode each : type) {
            if (each.asString().equals("string")) {
                return true;
            }
        }
        return false;
    }

    /** The first JSON object written at the start of a line: the type's schema, which every template puts first. */
    private JsonNode schemaIn(String text) {
        int start = text.indexOf("\n{");
        if (start < 0) {
            throw new IllegalStateException("No schema in the prompt");
        }
        // pretty-printed, so the schema's closing brace is the first one at the start of a line
        int end = text.indexOf("\n}", start);
        return jsonMapper.readTree(text.substring(start + 1, end + 2));
    }

    /** Consolidation sends no images, so the type is read back from the first reading's summary. */
    private static String typeInReadings(String text) {
        int start = text.indexOf("Summary: " + SUMMARY_PREFIX);
        if (start < 0) {
            throw new IllegalStateException("No synthetic summary in the readings");
        }
        start += ("Summary: " + SUMMARY_PREFIX).length();
        return text.substring(start, text.indexOf('\n', start));
    }

    private ObjectNode object() {
        return jsonMapper.createObjectNode();
    }

    /** The marker colour nearest the pixel at 50 % width and 5 % height, inside the band. */
    private static Marker markerOf(Media page) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(page.getDataAsByteArray()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Color pixel = new Color(image.getRGB(image.getWidth() / 2, image.getHeight() / 20));
        return Arrays.stream(Marker.values())
                .min(Comparator.comparingInt(marker -> distance(marker.color, pixel)))
                .orElseThrow();
    }

    private static int distance(Color a, Color b) {
        int red = a.getRed() - b.getRed();
        int green = a.getGreen() - b.getGreen();
        int blue = a.getBlue() - b.getBlue();
        return red * red + green * green + blue * blue;
    }
}
