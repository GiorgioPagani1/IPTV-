package ru.rftv.app;
import java.util.Locale;
public final class Channel {
    public final String id, name, url, group;
    public Channel(String id,String name,String url,String group) {
        this.id=id==null?"":id;
        this.name=name==null?"Телеканал":name;
        this.url=url;
        this.group=group==null?"":group;
    }
    public String key() {
        return (id.isEmpty()?name:id).trim().toLowerCase(Locale.ROOT);
    }
}
