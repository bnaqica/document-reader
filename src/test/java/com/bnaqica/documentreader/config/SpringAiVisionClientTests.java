package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bnaqica.documentreader.client.DocumentStorageClient;
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
import com.openai.core.http.Headers;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import java.lang.reflect.RecordComponent;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The vision client against a stub ChatModel: what each call sends, what it returns, and how it is logged. */
class SpringAiVisionClientTests {

    static final DocumentReaderProperties.OpenAi SETTINGS =
            new DocumentReaderProperties.OpenAi("gpt-4o", 0.5, 0.7, 0.2);

    final StubChatModel chatModel = new StubChatModel();
    final DocumentStorageClient storage = mock(DocumentStorageClient.class);
    final OpenAiCallService callLog = mock(OpenAiCallService.class);
    final JsonMapper jsonMapper = JsonMapper.builder().build();

    final CallContext context = someContext();

    static CallContext someContext() {
        return CallContext.builder()
                .batchId(UUID.randomUUID())
                .sourceFileId(UUID.randomUUID())
                .documentId(UUID.randomUUID())
                .loanPurposeDescription("Home purchase: buying a primary residence")
                .build();
    }

    final List<PageImageRef> pages = List.of(
            new PageImageRef(1, "files/p1.png"),
            new PageImageRef(2, "files/p2.png"),
            new PageImageRef(3, "files/p3.png"));

    final List<CatalogEntry> catalog = List.of(
            new CatalogEntry("Pay stub", "A record of one pay period's earnings"),
            new CatalogEntry("W-2", "A yearly wage and tax statement"),
            new CatalogEntry("Bank statement", "A monthly account statement"));

    static final String CLASSIFICATION = """
            {"documentType": "Pay stub", "confidence": 0.9, "reasoning": "Shows gross and net pay"}""";

    SpringAiVisionClientTests() {
        when(storage.get(anyString())).thenAnswer(call -> pageBytes(call.getArgument(0)));
    }

    SpringAiVisionClient client() {
        return client(SETTINGS);
    }

    SpringAiVisionClient client(DocumentReaderProperties.OpenAi settings) {
        return new SpringAiVisionClient(chatModel, storage, callLog, jsonMapper, settings);
    }

    /** Distinct bytes per key, so a test can tell the images apart and check their order. */
    static byte[] pageBytes(String key) {
        return ("png:" + key).getBytes(StandardCharsets.US_ASCII);
    }

    static UserMessage userMessage(Prompt prompt) {
        return prompt.getUserMessage();
    }

    static OpenAiChatOptions options(Prompt prompt) {
        return (OpenAiChatOptions) prompt.getOptions();
    }

    @Test
    void classifySendsImagesCatalogAndPurpose() {
        chatModel.answer(CLASSIFICATION);

        client().classify(context, pages, catalog);

        Prompt prompt = chatModel.lastPrompt();
        List<Media> media = userMessage(prompt).getMedia();
        assertThat(media).hasSize(3);
        assertThat(media).allSatisfy(image -> assertThat(image.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG));
        assertThat(media)
                .extracting(Media::getDataAsByteArray)
                .containsExactly(pageBytes("files/p1.png"), pageBytes("files/p2.png"), pageBytes("files/p3.png"));
        assertThat(prompt.getContents())
                .contains("Pay stub", "A record of one pay period's earnings")
                .contains("W-2", "A yearly wage and tax statement")
                .contains("Bank statement", "A monthly account statement")
                .contains("Home purchase: buying a primary residence");
    }

    @Test
    void classifyReturnsTheAnswer() {
        chatModel.answer(CLASSIFICATION);

        ClassificationAnswer answer = client().classify(context, pages, catalog);

        assertThat(answer).isEqualTo(new ClassificationAnswer("Pay stub", 0.9, "Shows gross and net pay"));
    }

    @Test
    void classifyUsesClassificationTemperature() {
        chatModel.answer(CLASSIFICATION);

        client().classify(context, pages, catalog);

        assertThat(options(chatModel.lastPrompt()).getTemperature()).isEqualTo(0.7);
        assertThat(options(chatModel.lastPrompt()).getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void classifyAsksForTheClassificationSchema() {
        chatModel.answer(CLASSIFICATION);

        client().classify(context, pages, catalog);

        var format = options(chatModel.lastPrompt()).getResponseFormat();
        assertThat(format.getType().name()).isEqualTo("JSON_SCHEMA");
        assertThat(format.getStrict()).isTrue();
        assertThat(jsonMapper.readTree(format.getJsonSchema()).path("required").toString())
                .contains("documentType", "confidence", "reasoning");
    }

    /** Each purpose with an answer it accepts, and the call that makes it. */
    Map<OpenAiCallPurpose, Consumer<SpringAiVisionClient>> everyCall() {
        String extraction = """
                {"data": {"employer": "Acme", "grossPay": null}, "summary": "s"}""";
        Map<OpenAiCallPurpose, Consumer<SpringAiVisionClient>> calls = new EnumMap<>(OpenAiCallPurpose.class);
        calls.put(OpenAiCallPurpose.SEPARATE, c -> {
            chatModel.answer("{\"startPages\": [1], \"labels\": [\"Pay stub\"]}");
            c.separate(context, pages, List.of("Pay stub"));
        });
        calls.put(OpenAiCallPurpose.CLASSIFY, c -> {
            chatModel.answer(CLASSIFICATION);
            c.classify(context, pages, catalog);
        });
        calls.put(OpenAiCallPurpose.TIEBREAK, c -> {
            chatModel.answer(CLASSIFICATION);
            c.tiebreak(context, pages, catalog);
        });
        calls.put(OpenAiCallPurpose.CONFIRM_TYPE, c -> {
            chatModel.answer("{\"matchesExisting\": false, \"existingName\": null}");
            c.confirmNewType(context, pages, "Gift letter", catalog);
        });
        calls.put(OpenAiCallPurpose.DRAFT_SCHEMA, c -> {
            chatModel.answer(PAY_STUB_SCHEMA);
            c.draftSchema(context, pages, "Gift letter");
        });
        calls.put(OpenAiCallPurpose.EXTRACT, c -> {
            chatModel.answer(extraction);
            c.extract(context, pages, payStubSchema());
        });
        calls.put(OpenAiCallPurpose.CONSOLIDATE, c -> {
            chatModel.answer("{\"data\": {\"employer\": \"Acme\", \"grossPay\": null}, \"summary\": \"s\", "
                    + "\"disagreements\": []}");
            c.consolidate(context, payStubSchema(), List.of());
        });
        calls.put(OpenAiCallPurpose.FINAL, c -> {
            chatModel.answer(extraction);
            c.finalizeExtraction(
                    context,
                    pages,
                    payStubSchema(),
                    new ConsolidatedAnswer(payStubSchema(), "s", List.of()),
                    List.of());
        });
        return calls;
    }

    @ParameterizedTest
    @CsvSource({
        "SEPARATE, 0.5",
        "CLASSIFY, 0.7",
        "TIEBREAK, 0.7",
        "CONFIRM_TYPE, 0.2",
        "DRAFT_SCHEMA, 0.2",
        "EXTRACT, 0.2",
        "CONSOLIDATE, 0.2",
        "FINAL, 0.2"
    })
    void everyCallNamesTheConfiguredModelAndItsTemperature(OpenAiCallPurpose purpose, double temperature) {
        everyCall().get(purpose).accept(client(new DocumentReaderProperties.OpenAi("gpt-4.1", 0.5, 0.7, 0.2)));

        assertThat(options(chatModel.lastPrompt()).getModel()).isEqualTo("gpt-4.1");
        assertThat(options(chatModel.lastPrompt()).getTemperature()).isEqualTo(temperature);
    }

    @Test
    void separateSendsCatalogNames() {
        chatModel.answer("""
                {"startPages": [1, 3], "labels": ["Pay stub", "W-2"]}""");

        SeparationAnswer answer = client().separate(context, pages, List.of("Pay stub", "W-2", "Bank statement"));

        Prompt prompt = chatModel.lastPrompt();
        assertThat(userMessage(prompt).getMedia()).hasSize(3);
        assertThat(prompt.getContents())
                .contains("Pay stub", "W-2", "Bank statement", "Home purchase: buying a primary residence");
        assertThat(options(prompt).getTemperature()).isEqualTo(0.5);
        assertThat(answer).isEqualTo(new SeparationAnswer(List.of(1, 3), List.of("Pay stub", "W-2")));
    }

    @Test
    void tiebreakListsOnlyCandidates() {
        chatModel.answer(CLASSIFICATION);

        ClassificationAnswer answer = client().tiebreak(context, pages, catalog.subList(0, 2));

        Prompt prompt = chatModel.lastPrompt();
        assertThat(prompt.getContents())
                .contains("Pay stub", "W-2", "Home purchase: buying a primary residence")
                .doesNotContain("Bank statement");
        assertThat(userMessage(prompt).getMedia()).hasSize(3);
        assertThat(options(prompt).getTemperature()).isEqualTo(0.7);
        assertThat(answer.documentType()).isEqualTo("Pay stub");
    }

    @Test
    void confirmNewTypeSendsProposedName() {
        chatModel.answer("""
                {"matchesExisting": true, "existingName": "Pay stub"}""");

        TypeMatchAnswer answer = client().confirmNewType(context, pages, "Paycheck record", catalog);

        Prompt prompt = chatModel.lastPrompt();
        assertThat(prompt.getContents())
                .contains("Paycheck record", "Pay stub", "A record of one pay period's earnings", "Bank statement")
                .contains("Home purchase: buying a primary residence");
        assertThat(userMessage(prompt).getMedia()).hasSize(3);
        assertThat(options(prompt).getTemperature()).isEqualTo(0.2);
        assertThat(answer).isEqualTo(new TypeMatchAnswer(true, "Pay stub"));
    }

    @Test
    void confirmNewTypeReturnsNoMatch() {
        chatModel.answer("""
                {"matchesExisting": false, "existingName": null}""");

        assertThat(client().confirmNewType(context, pages, "Gift letter", catalog))
                .isEqualTo(new TypeMatchAnswer(false, null));
    }

    @Test
    void matchWithoutExistingNameIsInvalid() {
        chatModel.answer("""
                {"matchesExisting": true, "existingName": null}""");

        assertThatThrownBy(() -> client().confirmNewType(context, pages, "Paycheck record", catalog))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("CONFIRM_TYPE")
                .hasMessageContaining("invalid answer");
    }

    static final String PAY_STUB_SCHEMA = """
            {"type": "object",
             "properties": {"employer": {"type": "string"}, "grossPay": {"type": ["number", "null"]}},
             "required": ["employer", "grossPay"],
             "additionalProperties": false}""";

    JsonNode payStubSchema() {
        return jsonMapper.readTree(PAY_STUB_SCHEMA);
    }

    @Test
    void draftSchemaSendsTypeNameAndReturnsSchema() {
        chatModel.answer(PAY_STUB_SCHEMA);

        JsonNode schema = client().draftSchema(context, pages, "Gift letter");

        Prompt prompt = chatModel.lastPrompt();
        assertThat(prompt.getContents()).contains("Gift letter", "Home purchase: buying a primary residence");
        assertThat(userMessage(prompt).getMedia()).hasSize(3);
        assertThat(options(prompt).getTemperature()).isEqualTo(0.2);
        assertThat(options(prompt).getResponseFormat().getType().name()).isEqualTo("JSON_OBJECT");
        assertThat(schema).isEqualTo(payStubSchema());
    }

    @Test
    void draftSchemaMustPassSchemaRules() {
        chatModel.answer("""
                {"type": "object", "properties": {"employer": {"type": "string"}}}""");

        assertThatThrownBy(() -> client().draftSchema(context, pages, "Gift letter"))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("DRAFT_SCHEMA")
                .hasMessageContaining("schema rules");
    }

    @Test
    void extractRequestsStructuredOutputWithSchema() {
        chatModel.answer("""
                {"data": {"employer": "Acme", "grossPay": 2500.0}, "summary": "Pay stub from Acme"}""");

        ExtractionAnswer answer = client().extract(context, pages, payStubSchema());

        Prompt prompt = chatModel.lastPrompt();
        var format = options(prompt).getResponseFormat();
        assertThat(format.getType().name()).isEqualTo("JSON_SCHEMA");
        assertThat(format.getStrict()).isTrue();
        JsonNode requested = jsonMapper.readTree(format.getJsonSchema());
        assertThat(requested.path("properties").path("data")).isEqualTo(payStubSchema());
        assertThat(requested.path("properties").path("summary").path("type").asString())
                .isEqualTo("string");
        assertThat(DataSchemaRules.violations(requested)).isEmpty();
        assertThat(userMessage(prompt).getMedia()).hasSize(3);
        assertThat(prompt.getContents()).contains("Home purchase: buying a primary residence");
        assertThat(options(prompt).getTemperature()).isEqualTo(0.2);
        assertThat(answer.data().path("employer").asString()).isEqualTo("Acme");
        assertThat(answer.summary()).isEqualTo("Pay stub from Acme");
    }

    @Test
    void consolidateSendsNoImages() {
        chatModel.answer("""
                {"data": {"employer": "Acme", "grossPay": 2500.0}, "summary": "Pay stub from Acme",
                 "disagreements": ["grossPay"]}""");
        List<ExtractionAnswer> passes = List.of(
                new ExtractionAnswer(jsonMapper.readTree("{\"employer\": \"Acme\", \"grossPay\": 2500.0}"), "first"),
                new ExtractionAnswer(
                        jsonMapper.readTree("{\"employer\": \"Acme Inc\", \"grossPay\": 2500.0}"), "second"),
                new ExtractionAnswer(jsonMapper.readTree("{\"employer\": \"Acme\", \"grossPay\": 2050.0}"), "third"));

        ConsolidatedAnswer answer = client().consolidate(context, payStubSchema(), passes);

        Prompt prompt = chatModel.lastPrompt();
        assertThat(userMessage(prompt).getMedia()).isEmpty();
        assertThat(prompt.getContents())
                .contains("grossPay", "Acme Inc", "2050.0", "first", "second", "third")
                .contains("Home purchase: buying a primary residence");
        JsonNode requested =
                jsonMapper.readTree(options(prompt).getResponseFormat().getJsonSchema());
        assertThat(requested.path("properties").path("data")).isEqualTo(payStubSchema());
        assertThat(DataSchemaRules.violations(requested)).isEmpty();
        assertThat(answer.disagreements()).containsExactly("grossPay");
        assertThat(answer.summary()).isEqualTo("Pay stub from Acme");
    }

    @Test
    void finalizeSendsDraftAndValidationErrors() {
        chatModel.answer("""
                {"data": {"employer": "Acme", "grossPay": 2500.0}, "summary": "Pay stub from Acme"}""");
        ConsolidatedAnswer draft = new ConsolidatedAnswer(
                jsonMapper.readTree("{\"employer\": \"Acme\", \"grossPay\": 2050.0}"),
                "Draft summary",
                List.of("grossPay"));

        ExtractionAnswer answer = client().finalizeExtraction(
                        context, pages, payStubSchema(), draft, List.of("$.grossPay: must be a number"));

        Prompt prompt = chatModel.lastPrompt();
        assertThat(userMessage(prompt).getMedia()).hasSize(3);
        assertThat(prompt.getContents())
                .contains("2050.0", "Draft summary", "$.grossPay: must be a number", "employer")
                .contains("Home purchase: buying a primary residence");
        assertThat(options(prompt).getTemperature()).isEqualTo(0.2);
        assertThat(answer.data().path("grossPay").asDouble()).isEqualTo(2500.0);
    }

    static RateLimitException rateLimited() {
        return RateLimitException.builder().headers(Headers.builder().build()).build();
    }

    @Test
    void httpErrorBecomesVisionException() {
        chatModel.fail(new RuntimeException("wrapped", rateLimited()));

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOfSatisfying(VisionException.class, e -> {
                    assertThat(e.getPurpose()).isEqualTo(OpenAiCallPurpose.CLASSIFY);
                    assertThat(e.getHttpStatus()).isEqualTo(429);
                    assertThat(e.getMessage()).contains("CLASSIFY", "429");
                });
    }

    @Test
    void unreachableOpenAiBecomesVisionException() {
        chatModel.fail(new OpenAIIoException("connection refused"));

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOfSatisfying(
                        VisionException.class,
                        e -> assertThat(e.getHttpStatus()).isNull());
    }

    @Test
    void invalidJsonBecomesVisionException() {
        chatModel.answer("The employer is Acme and the gross pay is 2500.");

        assertThatThrownBy(() -> client().extract(context, pages, payStubSchema()))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("EXTRACT")
                .hasMessageContaining("invalid JSON")
                .hasMessageNotContaining("Acme");
    }

    @Test
    void emptyAnswerBecomesVisionException() {
        chatModel.answerNothing();

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("no answer");
    }

    @Test
    void blankAnswerBecomesVisionException() {
        chatModel.answer("  ");

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("no answer");
    }

    @Test
    void confidenceOutOfRangeIsInvalid() {
        chatModel.answer("""
                {"documentType": "Pay stub", "confidence": 1.5, "reasoning": "r"}""");

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("invalid answer");
    }

    @Test
    void missingTypeNameIsInvalid() {
        chatModel.answer("""
                {"confidence": 0.8, "reasoning": "r"}""");

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("invalid answer");
    }

    @Test
    void missingPageImageBecomesVisionException() {
        when(storage.get("files/p2.png")).thenThrow(new StorageException("get", "files/p2.png", null));

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("page image unavailable");
        assertThat(chatModel.prompts).isEmpty();
        verifyNoInteractions(callLog);
    }

    OpenAiCallRecord recordedCall() {
        ArgumentCaptor<OpenAiCallRecord> captor = ArgumentCaptor.forClass(OpenAiCallRecord.class);
        verify(callLog).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void everyCallRecordsOpenAiCall() {
        chatModel.answer(CLASSIFICATION, 1200, 80);

        client().classify(context, pages, catalog);

        OpenAiCallRecord call = recordedCall();
        assertThat(call.batchId()).isEqualTo(context.batchId());
        assertThat(call.sourceFileId()).isEqualTo(context.sourceFileId());
        assertThat(call.documentId()).isEqualTo(context.documentId());
        assertThat(call.purpose()).isEqualTo(OpenAiCallPurpose.CLASSIFY);
        assertThat(call.model()).isEqualTo("gpt-4o");
        assertThat(call.latencyMs()).isNotNegative();
        assertThat(call.promptTokens()).isEqualTo(1200);
        assertThat(call.completionTokens()).isEqualTo(80);
        assertThat(call.httpStatus()).isEqualTo(200);
        assertThat(call.error()).isNull();
    }

    /** The log record has nowhere to put a prompt or an answer. */
    @Test
    void callRecordHoldsNoBodies() {
        assertThat(OpenAiCallRecord.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactlyInAnyOrder(
                        "batchId",
                        "sourceFileId",
                        "documentId",
                        "purpose",
                        "model",
                        "latencyMs",
                        "promptTokens",
                        "completionTokens",
                        "httpStatus",
                        "error");
    }

    @ParameterizedTest
    @CsvSource({"SEPARATE", "CLASSIFY", "TIEBREAK", "CONFIRM_TYPE", "DRAFT_SCHEMA", "EXTRACT", "CONSOLIDATE", "FINAL"})
    void eachCallIsRecordedOnceUnderItsPurpose(OpenAiCallPurpose purpose) {
        everyCall().get(purpose).accept(client());

        assertThat(recordedCall().purpose()).isEqualTo(purpose);
    }

    @Test
    void failedCallIsRecordedWithStatusAndError() {
        chatModel.fail(rateLimited());

        assertThatThrownBy(() -> client().classify(context, pages, catalog)).isInstanceOf(VisionException.class);

        OpenAiCallRecord call = recordedCall();
        assertThat(call.httpStatus()).isEqualTo(429);
        assertThat(call.error()).isEqualTo("HTTP 429");
        assertThat(call.promptTokens()).isNull();
    }

    @Test
    void unusableAnswerIsRecordedWithItsReason() {
        chatModel.answer("The employer is Acme.", 500, 20);

        assertThatThrownBy(() -> client().extract(context, pages, payStubSchema()))
                .isInstanceOf(VisionException.class);

        OpenAiCallRecord call = recordedCall();
        assertThat(call.httpStatus()).isEqualTo(200);
        assertThat(call.error()).isEqualTo("invalid JSON");
        assertThat(call.promptTokens()).isEqualTo(500);
    }

    @Test
    void separationIsRecordedWithoutDocument() {
        chatModel.answer("{\"startPages\": [1], \"labels\": [\"Pay stub\"]}");
        CallContext fileContext = CallContext.builder()
                .batchId(context.batchId())
                .sourceFileId(context.sourceFileId())
                .loanPurposeDescription("Refinance")
                .build();

        client().separate(fileContext, pages, List.of("Pay stub"));

        assertThat(recordedCall().documentId()).isNull();
        assertThat(recordedCall().purpose()).isEqualTo(OpenAiCallPurpose.SEPARATE);
    }

    @Test
    void recordFailureStillReturnsAnswer() {
        chatModel.answer(CLASSIFICATION);
        when(callLog.record(any())).thenThrow(new IllegalStateException("database down"));

        assertThat(client().classify(context, pages, catalog).documentType()).isEqualTo("Pay stub");
    }

    @Test
    void recordFailureKeepsTheCallsOwnError() {
        chatModel.fail(rateLimited());
        when(callLog.record(any())).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> client().classify(context, pages, catalog)).isInstanceOf(VisionException.class);
    }

    @Test
    void callInsideTransactionIsRefused() {
        TransactionTemplate transaction = new TransactionTemplate(new NoOpTransactionManager());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> client().classify(context, pages, catalog)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        assertThat(chatModel.prompts).isEmpty();
        verifyNoInteractions(callLog, storage);
    }

    /** Opens a real Spring transaction scope with no database behind it. */
    static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }

    /** Jackson quotes the offending text in its messages; none of it may travel with the error. */
    static void assertNoMessageInChainContains(Throwable failure, String text) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            assertThat(String.valueOf(cause.getMessage())).doesNotContain(text);
        }
    }

    @Test
    void invalidJsonErrorCarriesNoAnswerText() {
        chatModel.answer("Acme pays 2500");

        assertThatThrownBy(() -> client().extract(context, pages, payStubSchema()))
                .isInstanceOfSatisfying(VisionException.class, e -> assertNoMessageInChainContains(e, "Acme"));
    }

    @Test
    void invalidAnswerErrorCarriesNoAnswerText() {
        chatModel.answer("""
                {"documentType": "Pay stub", "confidence": "Acme", "reasoning": "r"}""");

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOfSatisfying(VisionException.class, e -> {
                    assertThat(e.getReason()).isEqualTo("invalid answer");
                    assertNoMessageInChainContains(e, "Acme");
                });
    }

    /** The violation names the drafted properties, so it isn't kept as the cause either. */
    @Test
    void refusedDraftedSchemaCarriesNoCause() {
        chatModel.answer("""
                {"type": "object", "properties": {"acmeEmployer": {"type": "string", "not": {"const": "x"}}},
                 "required": ["acmeEmployer"], "additionalProperties": false}""");

        assertThatThrownBy(() -> client().draftSchema(context, pages, "Gift letter"))
                .isInstanceOf(VisionException.class)
                .hasNoCause();
    }

    @Test
    void draftedSchemaWithKeywordStrictModeRejectsIsRefused() {
        chatModel.answer("""
                {"type": "object", "properties": {"employer": {"type": "string", "not": {"const": "x"}}},
                 "required": ["employer"], "additionalProperties": false}""");

        assertThatThrownBy(() -> client().draftSchema(context, pages, "Gift letter"))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("schema rules");
    }

    @Test
    void nullAnswerIsInvalid() {
        chatModel.answer("null");

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOf(VisionException.class)
                .hasMessageContaining("invalid answer");
        assertThat(recordedCall().error()).isEqualTo("invalid answer");
    }

    @Test
    void timeoutIsRecordedAsTimeout() {
        chatModel.fail(new OpenAIIoException("read failed", new SocketTimeoutException()));

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOfSatisfying(
                        VisionException.class, e -> assertThat(e.getReason()).isEqualTo("timeout"));
        assertThat(recordedCall().error()).isEqualTo("timeout");
        assertThat(recordedCall().httpStatus()).isNull();
    }

    @Test
    void unreachableOpenAiIsRecordedAsUnreachable() {
        chatModel.fail(new OpenAIIoException("connection refused"));

        assertThatThrownBy(() -> client().classify(context, pages, catalog)).isInstanceOf(VisionException.class);
        assertThat(recordedCall().error()).isEqualTo("OpenAI unreachable");
    }

    /** A failure inside Spring AI before anything is sent isn't OpenAI being down. */
    @Test
    void localFailureIsRecordedAsRequestFailed() {
        chatModel.fail(new IllegalArgumentException("options must be OpenAiChatOptions"));

        assertThatThrownBy(() -> client().classify(context, pages, catalog))
                .isInstanceOfSatisfying(
                        VisionException.class, e -> assertThat(e.getReason()).isEqualTo("request failed"));
        assertThat(recordedCall().error()).isEqualTo("request failed");
    }

    @Test
    void missingUsageIsRecordedAsUnknown() {
        chatModel.answerWithoutUsage(CLASSIFICATION);

        client().classify(context, pages, catalog);

        assertThat(recordedCall().promptTokens()).isNull();
        assertThat(recordedCall().completionTokens()).isNull();
    }
}
