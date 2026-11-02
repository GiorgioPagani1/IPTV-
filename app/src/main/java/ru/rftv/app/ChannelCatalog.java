package ru.rftv.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A deliberately small, manually reviewed shortlist of Russian TV stations.
 * Only stations with at least one public URL in the current playlist are shown.
 * One station = one row, regardless of the number of stream URLs.
 */
public final class ChannelCatalog {
    private static final class Entry {
        final String id, title, category;
        Entry(String id, String title, String category) {
            this.id = id;
            this.title = title;
            this.category = category;
        }
    }

    private static final Entry[] POPULAR = {
        new Entry("ChannelOne.ru", "Первый канал", "Основные"),
        new Entry("Russia1.ru", "Россия 1", "Основные"),
        new Entry("NTV.ru", "НТВ", "Основные"),
        new Entry("RENTV.ru", "РЕН ТВ", "Основные"),
        new Entry("5Channel.ru", "Пятый канал", "Основные"),
        new Entry("TNT.ru", "ТНТ", "Развлечения"),
        new Entry("STS.ru", "СТС", "Развлечения"),
        new Entry("Domashniy.ru", "Домашний", "Развлечения"),
        new Entry("TVCentr.ru", "ТВ Центр", "Основные"),
        new Entry("Zvezda.ru", "Звезда", "Основные"),
        new Entry("Carousel.ru", "Карусель", "Детям"),
        new Entry("TV3.ru", "ТВ-3", "Развлечения"),
        new Entry("Mir.ru", "МИР", "Основные"),
        new Entry("Russia24.ru", "Россия 24", "Новости"),
        new Entry("Friday.ru", "Пятница!", "Развлечения"),
        new Entry("MatchTV.ru", "Матч ТВ", "Спорт"),
        new Entry("RussiaK.ru", "Россия К", "Культура"),
        new Entry("OTR.ru", "ОТР", "Основные"),
        new Entry("MosfilmGoldCollection.ru", "Мосфильм. Золотая коллекция", "Кино"),
        new Entry("Domkino.ru", "Дом кино", "Кино"),
        new Entry("Solnce.ru", "Солнце", "Детям"),
        new Entry("MuzTV.ru", "МУЗ-ТВ", "Музыка"),
        new Entry("Che.ru", "Че!", "Развлечения"),
        new Entry("2x2.ru", "2×2", "Развлечения"),
        new Entry("U.ru", "Ю", "Развлечения"),
        new Entry("RBKTV.ru", "РБК", "Новости"),
        new Entry("Mult.ru", "Мульт", "Детям"),
        new Entry("STSLove.ru", "СТС Love", "Развлечения"),
        new Entry("TNT4.ru", "ТНТ4", "Развлечения"),
        new Entry("Spas.ru", "Спас", "Основные"),
        new Entry("360.ru", "360°", "Новости")
    };

    private ChannelCatalog() {}

    public static List<Channel> popularOnly(List<Channel> raw) {
        List<Channel> result = new ArrayList<>();
        for (Entry entry : POPULAR) {
            List<Channel> matches = new ArrayList<>();
            for (Channel candidate : raw) {
                if (candidate.id == null) continue;
                String id = candidate.id.trim();
                int variant = id.indexOf('@');
                String base = variant == -1 ? id : id.substring(0, variant);
                if (!base.equalsIgnoreCase(entry.id)) continue;
                if (variant != -1) {
                    String suffix = id.substring(variant + 1).toLowerCase(Locale.ROOT);
                    if (!suffix.equals("sd") && !suffix.equals("hd") && !suffix.equals("4k")) continue;
                }
                String address = candidate.url;
                if (address == null || !(address.startsWith("https://") || address.startsWith("http://"))) continue;
                matches.add(candidate);
            }
            if (matches.isEmpty()) continue;
            Collections.sort(matches, Comparator.comparingInt(ChannelCatalog::quality).reversed());

            Set<String> addresses = new HashSet<>();
            Channel first = matches.get(0);
            Channel grouped = new Channel(entry.id, entry.title, first.url, entry.category, first.logo);
            grouped.sources.clear();
            for (Channel candidate : matches) {
                if (addresses.add(candidate.url)) {
                    grouped.sources.add(candidate.url);
                    if (grouped.sources.size() >= 5) break;
                }
            }
            result.add(grouped);
        }
        return result;
    }

    private static int quality(Channel c) {
        String url = c.url.toLowerCase(Locale.ROOT);
        int score = url.startsWith("https://") ? 50 : 0;
        if (url.contains("smotrim.ru") || url.contains("ntv.ru") ||
            url.contains("tvzvezda.ru") || url.contains("tvc-hls.cdnvideo.ru") ||
            url.contains("rbc.ru") || url.contains("mediacdn.ru") ||
            url.contains("facecast.io")) score += 80;
        if (url.contains(".m3u8")) score += 6;
        if (c.id.toLowerCase(Locale.ROOT).contains("@hd")) score += 3;
        if (url.contains("token=") || url.contains("?s=")) score -= 4;
        return score;
    }
}
