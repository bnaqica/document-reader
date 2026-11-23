package com.bnaqica.documentreader.config;

import com.bnaqica.documentreader.client.DocumentStorageClient;
import com.bnaqica.documentreader.client.VisionClient;
import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.CatalogEntry;
import com.bnaqica.documentreader.dto.ClassificationAnswer;
import com.bnaqica.documentreader.dto.ConsolidatedAnswer;
import com.bnaqica.documentreader.dto.ExtractionAnswer;
import com.bnaqica.documentreader.dto.OpenAiCallRecord;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.dto.SeparationAnswer;
import com.bnaqica.documentreader.dto.TypeMatchAnswer;
import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import com.bnaqica.documentreader.exception.StorageException;
import com.bnaqica.documentreader.exception.VisionException;
import com.bnaqica.documentreader.processors.DataSchemaRules;
import com.bnaqica.documentreader.service.OpenAiCallService;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel.ResponseFormat;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link VisionClient} over Spring AI's {@link ChatModel}. Each call is one system message, one user message holding
 * the step's prompt and the page images, and options naming the model, the step's temperature and the answer's JSON
 * Schema. The answer is parsed here, with the application's Jackson 3 mapper.
 */
@Slf4j
public class SpringAiVisionClient implements VisionClient {

    /** {@code <name>} delimiters, so JSON braces in the prompts need no escaping. */
    private static final StTemplateRenderer RENDERER = StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .build();

    private final ChatModel chatModel;
    private final DocumentStorageClient storage;
    private final OpenAiCallService callLog;
    private final JsonMapper jsonMapper;
    private final DocumentReaderProperties.OpenAi settings;
    private final String systemPrompt;
    private final String separationSchema;
    private final String classificationSchema;
    private final String typeMatchSchema;

    public SpringAiVisionClient(
            ChatModel chatModel,
            DocumentStorageClient storage,
            OpenAiCallService callLog,
            JsonMapper jsonMapper,
            DocumentReaderProperties.OpenAi settings) {
        this.chatModel = chatModel;
        this.storage = storage;
        this.callLog = callLog;
        this.jsonMapper = jsonMapper;
        this.settings = settings;
        this.systemPrompt = resource("prompts/system.st");
        this.separationSchema = resource("prompts/schemas/separation.json");
        this.classificationSchema = resource("prompts/schemas/classification.json");
        this.typeMatchSchema = resource("prompts/schemas/type-match.json");
    }

    @Override
    public SeparationAnswer separate(CallContext context, List<PageImageRef> pages, List<String> catalogNames) {
        return call(
                OpenAiCallPurpose.SEPARATE,
                context,
                "separate",
                Map.of("purpose", context.loanPurposeDescription(), "catalog", numbered(catalogNames)),
                pages,
                settings.separationTemperature(),
                strict(separationSchema),
                answer -> check(
                        jsonMapper.treeToValue(answer, SeparationAnswer.class),
                        separation -> separation.startPages() != null && separation.labels() != null));
    }

    @Override
    public ClassificationAnswer classify(CallContext context, List<PageImageRef> pages, List<CatalogEntry> catalog) {
        return call(
                OpenAiCallPurpose.CLASSIFY,
                context,
                "classify",
                Map.of("purpose", context.loanPurposeDescription(), "catalog", catalogText(catalog)),
                pages,
                settings.classificationTemperature(),
                strict(classificationSchema),
                this::classification);
    }

    @Override
    public ClassificationAnswer tiebreak(CallContext context, List<PageImageRef> pages, List<CatalogEntry> candidates) {
        return call(
                OpenAiCallPurpose.TIEBREAK,
                context,
                "tiebreak",
                Map.of("purpose", context.loanPurposeDescription(), "candidates", catalogText(candidates)),
                pages,
                settings.classificationTemperature(),
                strict(classificationSchema),
                this::classification);
    }

    @Override
    public TypeMatchAnswer confirmNewType(
            CallContext context, List<PageImageRef> pages, String proposedName, List<CatalogEntry> catalog) {
        return call(
                OpenAiCallPurpose.CONFIRM_TYPE,
                context,
                "confirm-type",
                Map.of(
                        "purpose", context.loanPurposeDescription(),
                        "proposedName", proposedName,
                        "catalog", catalogText(catalog)),
                pages,
                settings.extractionTemperature(),
                strict(typeMatchSchema),
                answer -> check(
                        jsonMapper.treeToValue(answer, TypeMatchAnswer.class),
                        match -> !match.matchesExisting() || hasText(match.existingName())));
    }

    @Override
    public JsonNode draftSchema(CallContext context, List<PageImageRef> pages, String typeName) {
        return call(
                OpenAiCallPurpose.DRAFT_SCHEMA,
                context,
                "draft-schema",
                Map.of("purpose", context.loanPurposeDescription(), "typeName", typeName),
                pages,
                settings.extractionTemperature(),
                // the answer is itself a schema, so no fixed schema can describe it
                ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build(),
                schema -> {
                    // the violations name the drafted properties, so they aren't kept on the error
                    if (!DataSchemaRules.violations(schema).isEmpty()) {
                        throw new VisionException(
                                OpenAiCallPurpose.DRAFT_SCHEMA, "drafted schema breaks the schema rules");
                    }
                    return schema;
                });
    }

    @Override
    public ExtractionAnswer extract(CallContext context, List<PageImageRef> pages, JsonNode schema) {
        return call(
                OpenAiCallPurpose.EXTRACT,
                context,
                "extract",
                Map.of("purpose", context.loanPurposeDescription(), "schema", pretty(schema)),
                pages,
                settings.extractionTemperature(),
                strict(extractionSchema(schema, false)),
                this::extraction);
    }

    @Override
    public ConsolidatedAnswer consolidate(CallContext context, JsonNode schema, List<ExtractionAnswer> passes) {
        List<String> readings = new ArrayList<>();
        for (int i = 0; i < passes.size(); i++) {
            readings.add("Reading " + (i + 1) + "\nSummary: " + passes.get(i).summary() + "\nData:\n"
                    + pretty(passes.get(i).data()));
        }
        return call(
                OpenAiCallPurpose.CONSOLIDATE,
                context,
                "consolidate",
                Map.of(
                        "purpose", context.loanPurposeDescription(),
                        "schema", pretty(schema),
                        "passes", String.join("\n\n", readings)),
                List.of(),
                settings.extractionTemperature(),
                strict(extractionSchema(schema, true)),
                answer -> check(
                        jsonMapper.treeToValue(answer, ConsolidatedAnswer.class),
                        merged -> merged.data() != null && merged.summary() != null && merged.disagreements() != null));
    }

    @Override
    public ExtractionAnswer finalizeExtraction(
            CallContext context,
            List<PageImageRef> pages,
            JsonNode schema,
            ConsolidatedAnswer draft,
            List<String> validationErrors) {
        return call(
                OpenAiCallPurpose.FINAL,
                context,
                "final",
                Map.of(
                        "purpose", context.loanPurposeDescription(),
                        "schema", pretty(schema),
                        "draft", "Summary: " + draft.summary() + "\nData:\n" + pretty(draft.data()),
                        "disagreements", listOrNone(draft.disagreements()),
                        "validationErrors", listOrNone(validationErrors)),
                pages,
                settings.extractionTemperature(),
                strict(extractionSchema(schema, false)),
                this::extraction);
    }

    /**
     * Sends one request and parses its answer. Refused inside a transaction (§13). Logged once, after OpenAI answers or
     * fails; not logged when nothing was sent.
     */
    private <T> T call(
            OpenAiCallPurpose purpose,
            CallContext context,
            String template,
            Map<String, Object> variables,
            List<PageImageRef> pages,
            double temperature,
            ResponseFormat format,
            Function<JsonNode, T> parse) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // a call can take a minute; it mustn't hold a connection, and its log row must survive a rollback
            throw new IllegalStateException("Make " + purpose + " calls outside any database transaction");
        }
        String text = PromptTemplate.builder()
                .renderer(RENDERER)
                .resource(new ClassPathResource("prompts/" + template + ".st"))
                .variables(variables)
                .build()
                .render();
        UserMessage user =
                UserMessage.builder().text(text).media(images(purpose, pages)).build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(settings.model())
                .temperature(temperature)
                .responseFormat(format)
                .build();
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), user), options);

        long started = System.nanoTime();
        ChatResponse response = null;
        Integer status = null;
        String error = null;
        try {
            try {
                response = chatModel.call(prompt);
                status = 200;
            } catch (RuntimeException e) {
                status = httpStatus(e);
                error = status != null ? "HTTP " + status : failure(e);
                throw new VisionException(purpose, status != null ? "OpenAI returned an error" : error, status, e);
            }
            try {
                return answer(purpose, response, parse);
            } catch (VisionException e) {
                error = e.getReason();
                throw e;
            }
        } finally {
            record(context, purpose, Duration.ofNanos(System.nanoTime() - started), response, status, error);
        }
    }

    private <T> T answer(OpenAiCallPurpose purpose, ChatResponse response, Function<JsonNode, T> parse) {
        String answer = answerText(response);
        if (answer == null || answer.isBlank()) {
            throw new VisionException(purpose, "no answer");
        }
        JsonNode tree;
        try {
            tree = jsonMapper.readTree(answer);
        } catch (JacksonException e) {
            // Jackson's message quotes the answer, so it isn't kept as the cause.
            throw new VisionException(purpose, "invalid JSON");
        }
        try {
            return parse.apply(tree);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new VisionException(purpose, "invalid answer");
        }
    }

    /**
     * One call log row, never the prompt or the answer. A failure to write it is logged and swallowed: the call is
     * already paid for, and failing the step would only repeat it.
     */
    private void record(
            CallContext context,
            OpenAiCallPurpose purpose,
            Duration latency,
            ChatResponse response,
            Integer status,
            String error) {
        Usage usage = usage(response);
        try {
            callLog.record(OpenAiCallRecord.builder()
                    .batchId(context.batchId())
                    .sourceFileId(context.sourceFileId())
                    .documentId(context.documentId())
                    .purpose(purpose)
                    .model(settings.model())
                    .latencyMs(latency.toMillis())
                    .promptTokens(usage == null ? null : usage.getPromptTokens())
                    .completionTokens(usage == null ? null : usage.getCompletionTokens())
                    .httpStatus(status)
                    .error(error)
                    .build());
        } catch (RuntimeException e) {
            log.error("Could not log the {} OpenAI call for batch {}", purpose, context.batchId(), e);
        }
    }

    /** The response's token usage, or null when it reported none (Spring AI then gives zero tokens). */
    private static Usage usage(ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().getUsage();
        if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() == 0) {
            return null;
        }
        return usage;
    }

    private static String answerText(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }
        return response.getResult().getOutput().getText();
    }

    /** The status of the OpenAI error response anywhere in the cause chain, or null when no response came. */
    private static Integer httpStatus(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                return service.statusCode();
            }
        }
        return null;
    }

    /**
     * The short error for a call that got no response: a timeout, OpenAI being unreachable (an I/O failure), or the
     * request failing before it was sent.
     */
    private static String failure(Throwable failure) {
        boolean io = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException || cause instanceof HttpTimeoutException) {
                return "timeout";
            }
            io |= cause instanceof OpenAIIoException || cause instanceof IOException;
        }
        return io ? "OpenAI unreachable" : "request failed";
    }

    private ClassificationAnswer classification(JsonNode answer) {
        return check(
                jsonMapper.treeToValue(answer, ClassificationAnswer.class),
                classification -> hasText(classification.documentType())
                        && classification.confidence() >= 0
                        && classification.confidence() <= 1);
    }

    private ExtractionAnswer extraction(JsonNode answer) {
        return check(
                jsonMapper.treeToValue(answer, ExtractionAnswer.class),
                extraction -> extraction.data() != null && extraction.summary() != null);
    }

    /**
     * The answer's schema for extraction and consolidation: the type's schema as {@code data}, plus a summary and, for
     * consolidation, the disagreements. Type schemas follow the strict rules, so the wrapper does too.
     */
    private String extractionSchema(JsonNode dataSchema, boolean withDisagreements) {
        ObjectNode properties = jsonMapper.createObjectNode();
        properties.set("data", dataSchema);
        properties.putObject("summary").put("type", "string");
        ArrayNode required = jsonMapper.createArrayNode().add("data").add("summary");
        if (withDisagreements) {
            ObjectNode disagreements = properties.putObject("disagreements");
            disagreements.put("type", "array");
            disagreements.putObject("items").put("type", "string");
            required.add("disagreements");
        }
        ObjectNode schema = jsonMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.set("required", required);
        schema.put("additionalProperties", false);
        return jsonMapper.writeValueAsString(schema);
    }

    private String pretty(JsonNode node) {
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private static String listOrNone(List<String> items) {
        return items == null || items.isEmpty()
                ? "None."
                : String.join("\n", items.stream().map(item -> "- " + item).toList());
    }

    /** The answer parsed, but it is null, or a required value is missing or out of range. */
    private static <T> T check(T answer, Predicate<T> valid) {
        if (answer == null || !valid.test(answer)) {
            throw new IllegalArgumentException("answer fails its checks");
        }
        return answer;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Read before anything is sent, so a missing page costs no OpenAI call. */
    private List<Media> images(OpenAiCallPurpose purpose, List<PageImageRef> pages) {
        List<Media> images = new ArrayList<>();
        for (PageImageRef page : pages) {
            try {
                images.add(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(storage.get(page.s3Key()))));
            } catch (StorageException e) {
                throw new VisionException(purpose, "page image unavailable", null, e);
            }
        }
        return images;
    }

    /** A numbered list of {@code name — description}, one entry per line. */
    private static String catalogText(List<CatalogEntry> catalog) {
        return numbered(catalog.stream()
                .map(entry -> entry.name() + " — " + entry.description())
                .toList());
    }

    private static String numbered(List<String> lines) {
        return String.join(
                "\n",
                IntStream.range(0, lines.size())
                        .mapToObj(i -> (i + 1) + ". " + lines.get(i))
                        .toList());
    }

    private static ResponseFormat strict(String schema) {
        return ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(schema)
                .strict(true)
                .build();
    }

    private static String resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing classpath resource " + path, e);
        }
    }
}
