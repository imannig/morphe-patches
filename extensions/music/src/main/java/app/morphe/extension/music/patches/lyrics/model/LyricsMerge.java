/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

/**
 * Aligns a provider's embedded auxiliary lines (romanization) to the original lyrics lines,
 * the way the upstream scripts do it: every original line is matched with the auxiliary line
 * whose start time falls inside its window. The result always has one entry per original line
 * so it can be rendered by index, with an empty string where no auxiliary line lines up.
 */
public final class LyricsMerge {

    private LyricsMerge() {
    }

    /**
     * @return {@code true} when at least one line carries non-empty auxiliary text.
     */
    public static boolean hasText(@Nullable List<LyricsLine> lines) {
        if (lines == null) {
            return false;
        }
        for (LyricsLine line : lines) {
            if (line.text() != null && !line.text().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean anyWordHasRomaji(@Nullable List<LyricsLine> lines) {
        if (lines == null) {
            return false;
        }
        for (LyricsLine line : lines) {
            for (Word word : line.words()) {
                if (word.romaji() != null && !word.romaji().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    public static Map<String, List<LyricsLine>> singleLanguageTranslations(
            @Nullable List<LyricsLine> lines, String lang) {
        if (!hasText(lines)) {
            return null;
        }
        Map<String, List<LyricsLine>> map = new HashMap<>();
        map.put(lang, lines);
        return map;
    }

    @Nullable
    public static List<String> mapLinesOnline(List<String> lines,
                                       Function<List<String>, List<String>> batch) {
        if (!Utils.isNetworkConnected()) {
            return null;
        }

        List<String> toMap = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (!line.isEmpty()) {
                toMap.add(line);
            }
        }

        List<String> mapped = new ArrayList<>(toMap.size());
        try {
            for (List<String> b : TextTranslator.splitByCharacterBudget(
                    toMap, TextTranslator.MAXIMUM_BATCH_CHARACTERS)) {
                List<String> r = batch.apply(b);
                // A mismatched count cannot be mapped back safely, and showing lines under the
                // wrong lyrics is worse than showing no result at all.
                if (r.size() != b.size()) {
                    return null;
                }
                mapped.addAll(r);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "mapLinesOnline failure", ex);
            return null;
        }

        List<String> result = new ArrayList<>(lines.size());
        int next = 0;
        for (String line : lines) {
            result.add(line.isEmpty() ? "" : mapped.get(next++));
        }
        return result;
    }

    public static List<LyricsLine> mergeRomanization(List<LyricsLine> original,
                                                    @Nullable List<LyricsLine> auxiliary) {
        List<LyricsLine> result = new ArrayList<>(original.size());
        if (auxiliary == null || auxiliary.isEmpty()) {
            for (LyricsLine line : original) {
                result.add(new LyricsLine(line.startTimeMs(), ""));
            }
            return result;
        }

        List<LyricsLine> sorted = new ArrayList<>(auxiliary);
        sorted.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        int auxIdx = 0;
        final int auxCount = sorted.size();

        for (int i = 0, size = original.size(); i < size; i++) {
            LyricsLine orig = original.get(i);

            if (orig.isBG()) {
                result.add(new LyricsLine(orig.startTimeMs(), ""));
                continue;
            }

            final long winStart = orig.startTimeMs();
            long winEnd = Long.MAX_VALUE;
            for (int nextIdx = i + 1; nextIdx < size; nextIdx++) {
                LyricsLine nextOrig = original.get(nextIdx);
                if (!nextOrig.isBG() && nextOrig.startTimeMs() > winStart + 50L) {
                    winEnd = nextOrig.startTimeMs();
                    break;
                }
            }

            LyricsLine matchedLine = null;
            int bestIdx = -1;
            long bestDiff = Long.MAX_VALUE;
            for (int k = auxIdx; k < auxCount; k++) {
                LyricsLine cand = sorted.get(k);
                long auxStart = cand.startTimeMs();
                if (auxStart < winStart - 500L) {
                    if (k == auxIdx) {
                        auxIdx++;
                    }
                    continue;
                }
                if (auxStart >= winEnd) {
                    break;
                }
                long diff = Math.abs(auxStart - winStart);
                if (diff < bestDiff && diff <= 2500L) {
                    bestDiff = diff;
                    bestIdx = k;
                    matchedLine = cand;
                }
                if (auxStart >= winStart && diff <= 150L) {
                    break;
                }
            }
            if (bestIdx >= 0) {
                auxIdx = bestIdx + 1;
            }
            String matched = matchedLine != null ? matchedLine.text() : "";
            if (matchedLine != null && matchedLine.hasWords()) {
                result.add(new LyricsLine(winStart, matchedLine.endTimeMs(), matched == null ? "" : matched,
                        matchedLine.words(), orig.agentId(), orig.isDuet(), orig.isBG(), orig.songPart()));
            } else {
                result.add(new LyricsLine(winStart, matched == null ? "" : matched));
            }
        }
        return result;
    }
}
