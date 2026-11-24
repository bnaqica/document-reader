package com.bnaqica.documentreader.processors;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The classification vote (§11). A candidate is a name key: the resolved type's key, or the key of a name the catalog
 * doesn't have yet, so a name that isn't a type until it wins can still be counted.
 */
@Component
public class TypeVoteProcessor {

    /** One counted run: the candidate key it voted for, and how confident it was (0 to 1). */
    public record ResolvedVote(String candidate, double confidence) {}

    /** {@code winner} is present when one candidate has the most votes; otherwise {@code tied} holds the top ones. */
    public record VoteOutcome(Optional<String> winner, List<String> tied, Map<String, Long> counts) {}

    public VoteOutcome tally(List<ResolvedVote> votes) {
        Map<String, Long> counts = votes.stream()
                .collect(Collectors.groupingBy(ResolvedVote::candidate, LinkedHashMap::new, Collectors.counting()));
        long top = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        List<String> leaders = counts.entrySet().stream()
                .filter(entry -> entry.getValue() == top)
                .map(Map.Entry::getKey)
                .toList();
        if (leaders.size() == 1) {
            return new VoteOutcome(Optional.of(leaders.getFirst()), List.of(), counts);
        }
        return new VoteOutcome(Optional.empty(), leaders, counts);
    }

    /**
     * The tied candidate whose votes have the highest average confidence (0 for one with no votes); equal averages go
     * to the smaller key, so the outcome is repeatable.
     */
    public String byConfidence(List<ResolvedVote> all, List<String> tied) {
        Comparator<String> byAverage = Comparator.comparingDouble(candidate -> averageConfidence(all, candidate));
        return tied.stream()
                .min(byAverage.reversed().thenComparing(Comparator.naturalOrder()))
                .orElseThrow(() -> new IllegalArgumentException("No tied candidates"));
    }

    private static double averageConfidence(List<ResolvedVote> all, String candidate) {
        return all.stream()
                .filter(vote -> vote.candidate().equals(candidate))
                .mapToDouble(ResolvedVote::confidence)
                .average()
                .orElse(0);
    }
}
