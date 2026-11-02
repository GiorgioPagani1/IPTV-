package ru.rftv.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class Channel {
    public final String id, name, url, group, logo;
    public final List<String> sources = new ArrayList<>();

    public Channel(String id, String name, String url, String group, String logo) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "Телеканал" : name;
        this.url = url;
        this.group = group == null ? "" : group;
        this.logo = logo == null ? "" : logo;
        if (url != null && !url.isEmpty()) sources.add(url);
    }

    public String key() {
        return (id.isEmpty() ? name : id).trim().toLowerCase(Locale.ROOT);
    }
}
