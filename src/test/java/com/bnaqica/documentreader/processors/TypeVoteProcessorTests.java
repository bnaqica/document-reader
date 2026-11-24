package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;

import com.bnaqica.documentreader.processors.TypeVoteProcessor.ResolvedVote;
import com.bnaqica.documentreader.processors.TypeVoteProcessor.VoteOutcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypeVoteProcessorTests {

    private final TypeVoteProcessor votes = new TypeVoteProcessor();

    @Test
    void tallyPicksPlurality() {
        VoteOutcome outcome = votes.tally(votes("paystub", 4, "w2form", 1));

        assertThat(outcome.winner()).contains("paystub");
        assertThat(outcome.tied()).isEmpty();
        assertThat(outcome.counts()).containsEntry("paystub", 4L).containsEntry("w2form", 1L);
    }

    @Test
    void tallyReportsTie() {
        List<ResolvedVote> cast = votes("paystub", 2, "w2form", 2);
        cast.add(new ResolvedVote("bankstatement", 0.9));

        VoteOutcome outcome = votes.tally(cast);

        assertThat(outcome.winner()).isEmpty();
        assertThat(outcome.tied()).containsExactlyInAnyOrder("paystub", "w2form");
    }

    @Test
    void tallyOfNothingHasNoWinner() {
        VoteOutcome outcome = votes.tally(List.of());

        assertThat(outcome.winner()).isEmpty();
        assertThat(outcome.tied()).isEmpty();
    }

    @Test
    void byConfidencePicksHighestAverage() {
        List<ResolvedVote> all = List.of(
                new ResolvedVote("paystub", 0.9),
                new ResolvedVote("paystub", 0.5), // average 0.7
                new ResolvedVote("w2form", 0.8), // average 0.8
                new ResolvedVote("bankstatement", 0.99)); // not tied

        assertThat(votes.byConfidence(all, List.of("paystub", "w2form"))).isEqualTo("w2form");
    }

    @Test
    void byConfidenceTieUsesSmallerKey() {
        List<ResolvedVote> all = List.of(new ResolvedVote("w2form", 0.6), new ResolvedVote("paystub", 0.6));

        assertThat(votes.byConfidence(all, List.of("w2form", "paystub"))).isEqualTo("paystub");
    }

    @Test
    void byConfidenceWithNoVotesForACandidateCountsItAsZero() {
        List<ResolvedVote> all = List.of(new ResolvedVote("paystub", 0.2));

        assertThat(votes.byConfidence(all, List.of("w2form", "paystub"))).isEqualTo("paystub");
    }

    private static List<ResolvedVote> votes(String first, int firstCount, String second, int secondCount) {
        List<ResolvedVote> cast = new ArrayList<>();
        for (int i = 0; i < firstCount; i++) {
            cast.add(new ResolvedVote(first, 0.9));
        }
        for (int i = 0; i < secondCount; i++) {
            cast.add(new ResolvedVote(second, 0.7));
        }
        return cast;
    }
}
