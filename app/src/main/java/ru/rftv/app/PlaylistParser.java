package ru.rftv.app;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
public final class PlaylistParser {
    private static final Pattern ATTR=Pattern.compile("([\\w-]+)=\\\"([^\\\"]*)\\\"");
    private PlaylistParser(){}
    public static List<Channel> parse(String m3u) {
        List<Channel> out=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        if(m3u==null)return out;
        String id="",group="",name=null;
        for(String raw:m3u.split("\\r?\\n")) {
            String line=raw.trim();
            if(line.startsWith("#EXTINF:")) {
                id=attr(line,"tvg-id");
                group=attr(line,"group-title");
                int comma=line.lastIndexOf(',');
                name=comma>=0?line.substring(comma+1).trim():id;
                if(name.isEmpty())name="Телеканал";
                name=name.replaceAll("\\s*\\[(?:Not 24/7|Geo-blocked)\\]","").trim();
            } else if(name!=null && !line.startsWith("#") && !line.isEmpty()) {
                if(line.startsWith("https://") || line.startsWith("http://")) {
                    String uniq=(id+"|"+name+"|"+line).toLowerCase(Locale.ROOT);
                    if(seen.add(uniq))out.add(new Channel(id,name,line,group));
                }
                name=null; id=""; group="";
            }
        }
        return out;
    }
    private static String attr(String line,String key) {
        Matcher m=ATTR.matcher(line);
        while(m.find())if(key.equals(m.group(1)))return m.group(2);
        return "";
    }
}
