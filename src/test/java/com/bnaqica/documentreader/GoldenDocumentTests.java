package com.bnaqica.documentreader;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * The golden suite: the synthetic fixtures through the whole pipeline and the <b>real</b> OpenAI model, to measure
 * separation and classification accuracy and token use before tuning run counts or prompts. Runs only through
 * {@code ./gradlew goldenTest} with {@code OPENAI_API_KEY} set, never in {@code build}, because every run costs real
 * calls. It reports accuracy rather than asserting it; it fails only when a batch doesn't finish.
 */
@Slf4j
@Tag("golden")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GoldenDocumentTests extends PipelineTestSupport {

    /** The real model is slower than the pipeline tests' deadline allows. */
    private static final Duration GOLDEN_DEADLINE = Duration.ofMinutes(10);

    record Expected(int file, int start, int end, String type) {}

    record Actual(int file, int start, int end, String type) {}

    /**
     * Every batch is run and reported before anything fails, since each one is paid for. Separation counts only the
     * boundaries after page 1, which every file has anyway: recall is the expected ones found, and precision is the
     * found ones that were expected, so splitting every page scores badly too.
     */
    @Test
    void measuresAccuracyAndCostAgainstTheRealModel() throws Exception {
        JsonNode expectations = jsonMapper.readTree(Fixtures.resource("golden/expectations.json"));
        int boundariesExpected = 0;
        int boundariesFound = 0;
        int boundariesCorrect = 0;
        Map<String, int[]> perType = new TreeMap<>(); // type -> {correct, total}
        List<String> rows = new ArrayList<>();
        List<String> unfinished = new ArrayList<>();

        for (JsonNode batch : expectations.get("batches")) {
            String name = batch.get("name").asString();
            List<Map.Entry<String, byte[]>> files = new ArrayList<>();
            for (JsonNode file : batch.get("files")) {
                files.add(Map.entry(file.asString(), Fixtures.resource("fixtures/" + file.asString())));
            }
            HttpResponse<String> response = submit(false, null, files);
            assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
            UUID batchId = UUID.fromString(
                    jsonMapper.readTree(response.body()).get("batchId").asString());
            String status;
            try {
                status = awaitFinal(batchId, GOLDEN_DEADLINE);
            } catch (AssertionError notFinished) {
                unfinished.add(name);
                rows.add(String.format("%-22s %-20s tokens %,d", name, "NOT FINISHED", tokens(batchId)));
                continue;
            }

            List<Expected> expected = new ArrayList<>();
            for (JsonNode document : batch.get("documents")) {
                expected.add(new Expected(
                        document.get("file").asInt(),
                        document.get("start").asInt(),
                        document.get("end").asInt(),
                        document.get("type").asString()));
            }
            List<Actual> actual = actualDocuments(getBatch(batchId));

            Set<List<Integer>> expectedStarts = new HashSet<>();
            expected.stream()
                    .filter(document -> document.start() > 1)
                    .forEach(document -> expectedStarts.add(List.of(document.file(), document.start())));
            Set<List<Integer>> foundStarts = new HashSet<>();
            actual.stream()
                    .filter(document -> document.start() > 1)
                    .forEach(document -> foundStarts.add(List.of(document.file(), document.start())));
            Set<List<Integer>> correctStarts = new HashSet<>(foundStarts);
            correctStarts.retainAll(expectedStarts);

            for (Expected document : expected) {
                int[] score = perType.computeIfAbsent(document.type(), type -> new int[2]);
                score[1]++;
                boolean classified = actual.stream()
                        .anyMatch(found -> found.file() == document.file()
                                && found.start() == document.start()
                                && found.end() == document.end()
                                && document.type().equals(found.type()));
                if (classified) {
                    score[0]++;
                }
            }
            boundariesExpected += expectedStarts.size();
            boundariesFound += foundStarts.size();
            boundariesCorrect += correctStarts.size();
            rows.add(String.format(
                    "%-22s %-20s boundaries %d of %d expected, %d found  documents %d (expected %d)  tokens %,d",
                    name,
                    status,
                    correctStarts.size(),
                    expectedStarts.size(),
                    foundStarts.size(),
                    actual.size(),
                    expected.size(),
                    tokens(batchId)));
        }

        log.info("Golden suite results");
        rows.forEach(row -> log.info("  {}", row));
        log.info("  Separation recall: {}", percent(boundariesCorrect, boundariesExpected));
        log.info("  Separation precision: {}", percent(boundariesCorrect, boundariesFound));
        perType.forEach((type, score) -> log.info("  Classification {}: {}", type, percent(score[0], score[1])));
        assertThat(unfinished)
                .as("batches not final within %s", GOLDEN_DEADLINE)
                .isEmpty();
    }

    /** Every document in the result, with its file as an index rather than an id. */
    private static List<Actual> actualDocuments(JsonNode result) {
        Map<String, Integer> fileIndex = new HashMap<>();
        for (JsonNode file : result.get("files")) {
            fileIndex.put(file.get("id").asString(), file.get("index").asInt());
        }
        List<Actual> documents = new ArrayList<>();
        for (JsonNode document : result.get("documents")) {
            JsonNode type = document.get("documentType");
            documents.add(new Actual(
                    fileIndex.get(document.get("sourceFileId").asString()),
                    document.get("pages").get("start").asInt(),
                    document.get("pages").get("end").asInt(),
                    type == null || type.isNull() ? null : type.get("name").asString()));
        }
        return documents;
    }

    private long tokens(UUID batchId) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(coalesce(prompt_tokens, 0) + coalesce(completion_tokens, 0)), 0)
                FROM document_reader_integration.openai_call WHERE batch_id = ?""", Long.class, batchId);
    }

    private static String percent(int correct, int total) {
        return String.format("%d/%d (%.0f %%)", correct, total, total == 0 ? 0.0 : 100.0 * correct / total);
    }
}
