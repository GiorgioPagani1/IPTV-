package ru.rftv.app;

import android.content.Context;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/** Downloads an optional, public XMLTV guide. Failures never block live television. */
public final class TvGuide {
    private static final String EPG_URL="https://free-epg.de/api/epg/ru.xml.gz";
    private static final long CACHE_DURATION_MS=6*60*60*1000L;
    private static final int MAX_COMPRESSED_BYTES=40*1024*1024;

    public static final class Show {
        public final String title;
        public final long start, end;
        Show(String title,long start,long end) {this.title=title;this.start=start;this.end=end;}
    }
    public static final class Slot {
        public Show now, next;
        public final List<Show> schedule = new ArrayList<>();
    }
    private final Map<String,Slot> guide = new HashMap<>();
    private boolean loaded=false;

    public synchronized Slot forChannel(Channel c) {
        if(c==null) return null;
        String key=canonical(c.id);
        Slot s=guide.get(key);
        if(s==null) s=guide.get(normalize(c.name));
        if(s!=null && !s.schedule.isEmpty()) {
            long now=System.currentTimeMillis();
            s.now=null;
            s.next=null;
            for(Show show:s.schedule) {
                if(show.start<=now && show.end>now) s.now=show;
                if(show.start>now && (s.next==null || show.start<s.next.start)) s.next=show;
            }
        }
        return s;
    }
    public synchronized boolean hasLoaded() {return loaded;}
    public synchronized void setFrom(Map<String,Slot> data) {
        guide.clear();
        guide.putAll(data);
        loaded=true;
    }

    public static Map<String,Slot> fetch(Context context) throws Exception {
        File file=new File(context.getFilesDir(),"tv-guide-ru.xml.gz");
        boolean refresh=!file.isFile()||System.currentTimeMillis()-file.lastModified()>CACHE_DURATION_MS;
        if(refresh) {
            File tmp=new File(context.getFilesDir(),"tv-guide-ru.download");
            HttpURLConnection connection=null;
            try {
                connection=(HttpURLConnection)new URL(EPG_URL).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(19000);
                connection.setRequestProperty("User-Agent","RF-TV/2.2 Android");
                if(connection.getResponseCode()!=200) throw new Exception("EPG HTTP "+connection.getResponseCode());
                int total=0;
                try(InputStream input=connection.getInputStream();
                    BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))) {
                    byte[] buf=new byte[16384]; int len;
                    while((len=input.read(buf))!=-1) {
                        total+=len;
                        if(total>MAX_COMPRESSED_BYTES) throw new Exception("EPG file too large");
                        out.write(buf,0,len);
                    }
                }
                if(total<100) throw new Exception("Empty XMLTV");
                if(file.exists()) file.delete();
                if(!tmp.renameTo(file)) throw new Exception("Could not cache XMLTV");
            } catch(Exception error) {
                tmp.delete();
                if(!file.isFile()) throw error;
            } finally {if(connection!=null)connection.disconnect();}
        }
        return parse(file);
    }

    private static Map<String,Slot> parse(File file) throws Exception {
        Map<String,Slot> byId=new HashMap<>();
        Map<String,String> names=new HashMap<>();
        long now=System.currentTimeMillis();
        SimpleDateFormat fmt=new SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US);
        fmt.setLenient(false);
        XmlPullParser parser=Xml.newPullParser();
        try(InputStream raw=new BufferedInputStream(new FileInputStream(file))) {
            raw.mark(2);
            int a=raw.read(),b=raw.read();
            raw.reset();
            InputStream input=(a==0x1f&&b==0x8b)?new GZIPInputStream(raw):raw;
            parser.setInput(input,"UTF-8");
            String channelId=null, channelDisplay=null, progChannel=null, title=null;
            long start=0,stop=0;
            int event=parser.getEventType(), count=0;
            while(event!=XmlPullParser.END_DOCUMENT) {
                if(event==XmlPullParser.START_TAG) {
                    String tag=parser.getName();
                    if("channel".equals(tag)) {
                        channelId=parser.getAttributeValue(null,"id");
                        channelDisplay=null;
                    } else if("display-name".equals(tag)&&channelId!=null) {
                        channelDisplay=parser.nextText();
                    } else if("programme".equals(tag)) {
                        progChannel=parser.getAttributeValue(null,"channel");
                        start=parseTime(fmt,parser.getAttributeValue(null,"start"));
                        stop=parseTime(fmt,parser.getAttributeValue(null,"stop"));
                        title=null;
                    } else if("title".equals(tag)&&progChannel!=null&&title==null) {
                        title=parser.nextText();
                    }
                } else if(event==XmlPullParser.END_TAG) {
                    String tag=parser.getName();
                    if("channel".equals(tag)) {
                        if(channelId!=null && channelDisplay!=null)
                            names.put(canonical(channelId),normalize(channelDisplay));
                        channelId=null;channelDisplay=null;
                    } else if("programme".equals(tag)) {
                        if(title!=null && progChannel!=null && start>0 && stop>now &&
                            start<now+12*60*60*1000L && stop>start) {
                            String id=canonical(progChannel);
                            Slot slot=byId.get(id);
                            if(slot==null){slot=new Slot();byId.put(id,slot);}
                            Show show=new Show(title.trim(),start,stop);
                            if(slot.schedule.size()<160) slot.schedule.add(show);
                            if(start<=now && stop>now &&
                                (slot.now==null || show.start>slot.now.start)) slot.now=show;
                            else if(start>now && (slot.next==null || start<slot.next.start)) slot.next=show;
                        }
                        progChannel=null;title=null;
                        if(++count>250000) break;
                    }
                }
                event=parser.next();
            }
        }
        for(Slot slot:byId.values()) {
            slot.schedule.sort((a,b)->Long.compare(a.start,b.start));
        }
        Map<String,Slot> merged=new HashMap<>(byId);
        for(Map.Entry<String,String> pair:names.entrySet()) {
            Slot slot=byId.get(pair.getKey());
            if(slot!=null&&!merged.containsKey(pair.getValue())) merged.put(pair.getValue(),slot);
        }
        return merged;
    }

    public static String time(long millis) {
        SimpleDateFormat fmt=new SimpleDateFormat("HH:mm",Locale.getDefault());
        return fmt.format(new Date(millis));
    }

    private static long parseTime(SimpleDateFormat parser,String input) {
        if(input==null) return 0;
        try {
            String clean=input.trim().replaceAll("\\s+"," ");
            if(clean.matches("^\\d{14}\\s[+-]\\d{4}$"))
                return parser.parse(clean).getTime();
            if(clean.matches("^\\d{12}\\s[+-]\\d{4}$"))
                return new SimpleDateFormat("yyyyMMddHHmm Z",Locale.US).parse(clean).getTime();
            return 0;
        } catch(Exception ignored){return 0;}
    }

    private static String canonical(String id) {
        if(id==null) return "";
        String x=id.toLowerCase(Locale.ROOT).trim();
        int at=x.indexOf('@');
        return at<0?x:x.substring(0,at);
    }

    private static String normalize(String name) {
        if(name==null) return "";
        String s=name.toLowerCase(Locale.ROOT).replace('ё','е');
        return s.replaceAll("[^a-zа-я0-9]","").trim();
    }
}
