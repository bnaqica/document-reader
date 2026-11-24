package com.bnaqica.documentreader.processors;

import com.bnaqica.documentreader.dto.PageRange;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * The separation vote (§9): cleans each run's answer, splits a long file into overlapping windows, and decides each
 * page gap by a majority of one window's runs. Page numbers here are always the file's, counted from 1.
 */
@Component
public class BoundaryVoteProcessor {

    /** One window's successful runs, each the set of pages where it starts a document. */
    public record WindowRuns(PageRange window, List<SortedSet<Integer>> runs) {}

    /**
     * The pages in {@code 2..pageCount}, once each, sorted. Page 1 always starts a document, so it carries no vote; a
     * null list or entry counts as nothing (Review Focus #2).
     */
    public SortedSet<Integer> sanitize(List<Integer> startPages, int pageCount) {
        SortedSet<Integer> pages = new TreeSet<>();
        if (startPages != null) {
            startPages.stream()
                    .filter(Objects::nonNull)
                    .filter(page -> page >= 2 && page <= pageCount)
                    .forEach(pages::add);
        }
        return pages;
    }

    /**
     * Windows of {@code size} pages, each starting {@code size - overlap} after the one before, the last ending at the
     * last page. A file within one window is one window.
     *
     * @throws IllegalArgumentException unless {@code 1 <= overlap < size}
     */
    public List<PageRange> windows(int pageCount, int size, int overlap) {
        if (overlap < 1 || overlap >= size) {
            throw new IllegalArgumentException("A window overlap must be at least 1 and less than the window size");
        }
        List<PageRange> windows = new ArrayList<>();
        int start = 1;
        while (true) {
            int end = Math.min(start + size - 1, pageCount);
            windows.add(new PageRange(start, end));
            if (end == pageCount) {
                return windows;
            }
            start += size - overlap;
        }
    }

    /** One window over the whole file. */
    public List<PageRange> vote(List<SortedSet<Integer>> runs, int pageCount) {
        return voteWindows(List.of(new WindowRuns(new PageRange(1, pageCount), runs)), pageCount);
    }

    /**
     * For each gap before page p, the window holding pages p-1 and p where the gap sits furthest from an edge decides
     * (the earlier on a tie); p starts a document when more than half of that window's runs say so. The ranges cover
     * every page, in order.
     */
    public List<PageRange> voteWindows(List<WindowRuns> windows, int pageCount) {
        List<Integer> starts = new ArrayList<>(List.of(1));
        for (int page = 2; page <= pageCount; page++) {
            WindowRuns decider = decidingWindow(windows, page);
            if (decider != null && isMajority(decider.runs(), page)) {
                starts.add(page);
            }
        }
        List<PageRange> ranges = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) - 1 : pageCount;
            ranges.add(new PageRange(starts.get(i), end));
        }
        return ranges;
    }

    private static WindowRuns decidingWindow(List<WindowRuns> windows, int page) {
        WindowRuns best = null;
        int bestDistance = -1;
        for (WindowRuns candidate : windows) {
            PageRange window = candidate.window();
            if (window.start() > page - 1 || window.end() < page) {
                continue;
            }
            int distance = Math.min((page - 1) - window.start(), window.end() - page);
            if (distance > bestDistance) { // strictly greater: the earlier window wins a tie
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isMajority(List<SortedSet<Integer>> runs, int page) {
        long votes = runs.stream().filter(run -> run.contains(page)).count();
        return votes * 2 > runs.size();
    }
}
