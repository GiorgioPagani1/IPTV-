package ru.reelbot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Transparent local interpretation of supported editing instructions; no cloud AI. */
public final class EditInstructions {
    public enum Matching { BALANCED, MOTION, VISUAL_OBJECT }
    public final Matching matching;
    public final boolean toBeat;
    public final int segmentMs;

    private EditInstructions(Matching matching, boolean toBeat, int segmentMs) {
        this.matching = matching;
        this.toBeat = toBeat;
        this.segmentMs = segmentMs;
    }

    public static EditInstructions parse(String text, int defaultSegmentMs) {
        String s = text == null ? "" : text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        boolean motion = containsAny(s, "движен", "двигал", "двигает", "поворот", "направлен", "motion");
        boolean object = containsAny(s, "объект", "предмет", "цвет", "форм", "композиц", "силуэт", "object");
        Matching matching = motion && !object ? Matching.MOTION
            : object && !motion ? Matching.VISUAL_OBJECT : Matching.BALANCED;
        boolean beat = containsAny(s, "бит", "музык", "ритм", "тактк", "такт", "удар", "beat", "bpm");
        boolean slow = containsAny(s, "плавн", "неторопл", "спокойн", "длинн");
        boolean fast = containsAny(s, "быстр", "динамич", "коротк", "часты", "резк");
        int segment = slow && !fast ? 4400 : fast && !slow ? 2300 : defaultSegmentMs;
        return new EditInstructions(matching, beat, segment);
    }

    public String description() {
        String matchingDescription = matching == Matching.MOTION ? "склейки по направлению движения"
            : matching == Matching.VISUAL_OBJECT ? "склейки по сходству цвета и композиции"
            : "сбалансированный монтаж";
        return matchingDescription + (toBeat ? " · в ритм музыки" : "") + " · " + (segmentMs < 3000 ? "динамично" : "спокойно");
    }

    /**
     * Creates requested durations from detected beat positions. Each internal cut lies on a beat.
     * Without valid beats, uses steady nominal cuts. Output durations sum to targetMs.
     */
    public List<Long> durations(long targetMs, List<Long> beats) {
        if (targetMs < 800) return Collections.emptyList();
        ArrayList<Long> result = new ArrayList<>();
        long cursor = 0;
        while (cursor + 800 < targetMs) {
            long remaining = targetMs - cursor;
            if (remaining <= segmentMs + 850) {
                result.add(remaining);
                break;
            }
            long target = cursor + segmentMs;
            long cut = target;
            if (toBeat && beats != null && !beats.isEmpty()) {
                long bestDistance = Long.MAX_VALUE;
                for (long beat : beats) {
                    if (beat < cursor + 950 || beat > targetMs - 850) continue;
                    if (beat < cursor + segmentMs / 2 || beat > cursor + segmentMs * 3L / 2) continue;
                    long distance = Math.abs(beat - target);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        cut = beat;
                    }
                }
            }
            if (cut <= cursor || cut >= targetMs - 800) {
                result.add(remaining);
                break;
            }
            result.add(cut - cursor);
            cursor = cut;
        }
        return result;
    }

    private static boolean containsAny(String s, String... patterns) {
        for (String p : patterns) if (s.contains(p)) return true;
        return false;
    }
}
