package com.bnaqica.documentreader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.bnaqica.documentreader.FixtureGenerator.Marker;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.config.SpringAiVisionClient;
import com.bnaqica.documentreader.dto.CallContext;
import com.bnaqica.documentreader.dto.CatalogEntry;
import com.bnaqica.documentreader.dto.ConsolidatedAnswer;
import com.bnaqica.documentreader.dto.ExtractionAnswer;
import com.bnaqica.documentreader.dto.OpenAiCallRecord;
import com.bnaqica.documentreader.dto.PageImageRef;
import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import com.bnaqica.documentreader.exception.VisionException;
import com.bnaqica.documentreader.processors.DataSchemaValidator;
import com.bnaqica.documentreader.processors.DetectedType;
import com.bnaqica.documentreader.processors.ImageConversionProcessor;
import com.bnaqica.documentreader.service.OpenAiCallService;
import com.bnaqica.documentreader.service.RecordingStorage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stub model behind the real vision client, so every prompt is the real template and every answer passes the
 * client's own parsing and checks. The pages are the committed fixtures, rendered by the real converter.
 */
class PipelineChatModelTests {

    private static final ImageConversionProcessor CONVERTER = new ImageConversionProcessor(200, 2048, null);
    private static final List<CatalogEntry> CATALOG = List.of(
            new CatalogEntry("Pay stubs", "Earnings for one pay period"),
            new CatalogEntry("W-2 forms", "Annual wage and tax statement"));

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RecordingStorage storage = new RecordingStorage();
    private final OpenAiCallService callLog = mock(OpenAiCallService.class);
    private final PipelineChatModel model = new PipelineChatModel(jsonMapper);
    private final SpringAiVisionClient client = new SpringAiVisionClient(
            model, storage, callLog, jsonMapper, new DocumentReaderProperties.OpenAi("gpt-4o", 0.5, 0.7, 0.2));
    private final CallContext context = new CallContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Car");

    @Test
    void fixturesHaveTheExpectedPageCounts() throws IOException {
        assertThat(CONVERTER.pageCount(Fixtures.packet(), DetectedType.PDF)).isEqualTo(6);
        assertThat(CONVERTER.pageCount(Fixtures.license(), DetectedType.JPEG)).isEqualTo(1);
    }

    @Test
    void separationStartsWhereTheMarkerChanges() throws IOException {
        List<PageImageRef> pages = store(Fixtures.packet(), DetectedType.PDF);

        assertThat(client.separate(context, pages, List.of("Pay stubs")).startPages())
                .containsExactly(1, 3, 4);
    }

    @Test
    void separationOfAWindowNumbersFromItsFirstImage() throws IOException {
        List<PageImageRef> pages = store(Fixtures.packet(), DetectedType.PDF);

        assertThat(client.separate(context, pages.subList(2, 6), List.of("Pay stubs"))
                        .startPages())
                .containsExactly(1, 2);
    }

    @Test
    void classificationNamesEachPagesType() throws IOException {
        List<PageImageRef> packet = store(Fixtures.packet(), DetectedType.PDF);
        List<PageImageRef> license = store(Fixtures.license(), DetectedType.JPEG);

        List<String> types = new ArrayList<>();
        for (PageImageRef page : packet) {
            types.add(client.classify(context, List.of(page), CATALOG).documentType());
        }
        types.add(client.classify(context, license, CATALOG).documentType());

        assertThat(types)
                .containsExactly(
                        "Pay stubs",
                        "Pay stubs",
                        "W-2 forms",
                        "Bank statements",
                        "Bank statements",
                        "Bank statements",
                        "Driver's license or government photo ID");
    }

    @Test
    void tiebreakNamesTheMarkersType() throws IOException {
        List<PageImageRef> license = store(Fixtures.license(), DetectedType.JPEG);

        assertThat(client.tiebreak(context, license, CATALOG).documentType()).isEqualTo(Marker.LICENSE.typeName);
    }

    @Test
    void confirmationNeverMatchesAnExistingType() throws IOException {
        List<PageImageRef> license = store(Fixtures.license(), DetectedType.JPEG);

        assertThat(client.confirmNewType(context, license, "Photo card", CATALOG)
                        .matchesExisting())
                .isFalse();
    }

    @Test
    void draftingASchemaIsNeverExpected() throws IOException {
        List<PageImageRef> license = store(Fixtures.license(), DetectedType.JPEG);

        assertThatThrownBy(() -> client.draftSchema(context, license, "Photo card"))
                .isInstanceOf(VisionException.class);
    }

    @ParameterizedTest
    @EnumSource(Marker.class)
    void extractionDataIsSchemaValidAtEveryStage(Marker marker) throws IOException {
        JsonNode schema = Fixtures.seededSchema(jsonMapper, marker.typeName);
        List<PageImageRef> pages = pagesOf(marker);
        DataSchemaValidator validator = new DataSchemaValidator();

        ExtractionAnswer pass = client.extract(context, pages, schema);
        ConsolidatedAnswer draft = client.consolidate(context, schema, List.of(pass, pass));
        ExtractionAnswer last = client.finalizeExtraction(context, pages, schema, draft, List.of());

        assertThat(validator.validate(schema, pass.data())).isEmpty();
        assertThat(validator.validate(schema, draft.data())).isEmpty();
        assertThat(validator.validate(schema, last.data())).isEmpty();
        assertThat(draft.disagreements()).isEmpty();
        assertThat(last.summary()).isEqualTo("Synthetic " + marker.typeName);
        assertThat(last.data().properties())
                .anySatisfy(field -> assertThat(field.getValue().isString()).isTrue());
    }

    @Test
    void everyAnswerReportsTokenUsage() throws IOException {
        List<PageImageRef> license = store(Fixtures.license(), DetectedType.JPEG);

        client.classify(context, license, CATALOG);

        ArgumentCaptor<OpenAiCallRecord> logged = ArgumentCaptor.forClass(OpenAiCallRecord.class);
        verify(callLog).record(logged.capture());
        assertThat(logged.getValue().promptTokens()).isEqualTo(100);
        assertThat(logged.getValue().completionTokens()).isEqualTo(10);
    }

    @Test
    void answeredCallsAreCounted() throws IOException {
        List<PageImageRef> license = store(Fixtures.license(), DetectedType.JPEG);

        client.classify(context, license, CATALOG);
        client.tiebreak(context, license, CATALOG);

        assertThat(model.calls()).isEqualTo(2);
    }

    @Test
    void anUnknownPromptThrows() {
        assertThatThrownBy(() -> model.call(new Prompt("Tell me a joke")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown prompt");
    }

    @Test
    void theCrashHookThrowsAnErrorOnTheMatchingPurpose() throws IOException {
        List<PageImageRef> license = store(Fixtures.license(), DetectedType.JPEG);
        JsonNode schema = Fixtures.seededSchema(jsonMapper, Marker.LICENSE.typeName);
        model.crashOn(OpenAiCallPurpose.EXTRACT);

        assertThatThrownBy(() -> client.extract(context, license, schema))
                .isInstanceOf(PipelineChatModel.SimulatedCrash.class);
        assertThat(model.crashes().getCount()).isZero();
        // only the first matching call crashes
        assertThat(client.extract(context, license, schema).data()).isNotNull();
    }

    /** The marker's document: the licence, or its first page in the packet. */
    private List<PageImageRef> pagesOf(Marker marker) throws IOException {
        if (marker == Marker.LICENSE) {
            return store(Fixtures.license(), DetectedType.JPEG);
        }
        List<PageImageRef> packet = store(Fixtures.packet(), DetectedType.PDF);
        int firstPage = switch (marker) {
            case PAY_STUB -> 1;
            case W2 -> 3;
            default -> 4;
        };
        return List.of(packet.get(firstPage - 1));
    }

    private List<PageImageRef> store(byte[] file, DetectedType type) {
        List<PageImageRef> pages = new ArrayList<>();
        CONVERTER.forEachPngPage(file, type, page -> {
            String key = "pages/" + UUID.randomUUID() + ".png";
            storage.put(key, page.png(), "image/png");
            pages.add(new PageImageRef(page.pageNumber(), key));
        });
        return pages;
    }
}
