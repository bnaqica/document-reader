package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.processors.BoundaryVoteProcessor.WindowRuns;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoundaryVoteProcessorTests {

    private final BoundaryVoteProcessor votes = new BoundaryVoteProcessor();

    // --- sanitize

    @Test
    void sanitizeDropsOutOfRangeDuplicatesAndOne() {
        assertThat(votes.sanitize(List.of(0, 3, 3, 9, 2), 5)).containsExactly(2, 3);
        assertThat(votes.sanitize(List.of(1, 5, -2), 5)).containsExactly(5);
    }

    @Test
    void sanitizeTreatsNullAsEmpty() {
        assertThat(votes.sanitize(null, 5)).isEmpty();
        assertThat(votes.sanitize(Arrays.asList(2, null), 5)).containsExactly(2);
    }

    // --- windows

    @Test
    void windowsOverlap() {
        assertThat(votes.windows(40, 15, 2))
                .containsExactly(new PageRange(1, 15), new PageRange(14, 28), new PageRange(27, 40));
        assertThat(votes.windows(28, 15, 2)).containsExactly(new PageRange(1, 15), new PageRange(14, 28));
        assertThat(votes.windows(100, 15, 2)).hasSize(8).last().isEqualTo(new PageRange(92, 100));
    }

    @Test
    void windowsWithinOneWindow() {
        assertThat(votes.windows(6, 15, 2)).containsExactly(new PageRange(1, 6));
        assertThat(votes.windows(15, 15, 2)).containsExactly(new PageRange(1, 15));
        assertThat(votes.windows(1, 15, 2)).containsExactly(new PageRange(1, 1));
    }

    @ParameterizedTest
    @CsvSource({"15, 0", "15, 15", "15, 16", "0, 0"})
    void windowsRefuseBadOverlap(int size, int overlap) {
        assertThatThrownBy(() -> votes.windows(40, size, overlap)).isInstanceOf(IllegalArgumentException.class);
    }

    // --- vote

    @Test
    void majorityOfThreeDecidesEachGap() {
        assertThat(votes.vote(List.of(set(1, 3), set(1, 3, 5), set(1)), 6))
                .containsExactly(new PageRange(1, 2), new PageRange(3, 6));
    }

    @Test
    void pageOneAlwaysStarts() {
        assertThat(votes.vote(List.of(set(4), set(4), set(4)), 6))
                .containsExactly(new PageRange(1, 3), new PageRange(4, 6));
    }

    @Test
    void emptyRunMeansOneDocument() {
        assertThat(votes.vote(List.of(set(), set(), set()), 4)).containsExactly(new PageRange(1, 4));
        assertThat(votes.vote(List.of(set()), 1)).containsExactly(new PageRange(1, 1));
    }

    @Test
    void evenSplitOfSuccessfulRunsIsNoBoundary() {
        // Two of three runs succeeded and disagree: half isn't a majority
        assertThat(votes.vote(List.of(set(3), set()), 6)).containsExactly(new PageRange(1, 6));
    }

    @Test
    void gapInTwoWindowsUsesMoreCentralWindow() {
        // Gap 8|9: window 1-10 has 1 page after page 9, window 6-15 has 2 pages before page 8 and 6 after page 9
        List<WindowRuns> windows = List.of(
                new WindowRuns(new PageRange(1, 10), List.of(set(9), set(9), set(9))),
                new WindowRuns(new PageRange(6, 15), List.of(set(), set(), set())));

        assertThat(votes.voteWindows(windows, 15)).containsExactly(new PageRange(1, 15));
    }

    @Test
    void gapOnBothEdgesUsesEarlierWindow() {
        List<WindowRuns> windows = List.of(
                new WindowRuns(new PageRange(1, 15), List.of(set(15), set(15), set(15))),
                new WindowRuns(new PageRange(14, 28), List.of(set(), set(), set())));

        assertThat(votes.voteWindows(windows, 28)).containsExactly(new PageRange(1, 14), new PageRange(15, 28));
    }

    @Test
    void eachWindowDecidesTheGapsOnlyItSees() {
        List<WindowRuns> windows = List.of(
                new WindowRuns(new PageRange(1, 15), List.of(set(5), set(5), set(7))),
                new WindowRuns(new PageRange(14, 28), List.of(set(20), set(20, 25), set(25))));

        assertThat(votes.voteWindows(windows, 28))
                .containsExactly(
                        new PageRange(1, 4), new PageRange(5, 19), new PageRange(20, 24), new PageRange(25, 28));
    }

    private static SortedSet<Integer> set(Integer... pages) {
        return new TreeSet<>(List.of(pages));
    }
}
