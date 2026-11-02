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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

/** On-demand Russian EPG. Only channels in the user playlist are parsed into memory. */
public final class TvGuide {
    private static final long CACHE_MS=4*60*60*1000L;
    private static final long HOUR=3600000L;
    private static final int MAX_BYTES=45*1024*1024;

    private static final String[][] SOURCES={
        {"EPG.ONE Россия","https://epg.one/ru.xml.gz"},
        {"TeleGuide","https://download.teleguide.su/download/new3/xmltv.xml.gz"},
        {"IPTVX","https://iptvx.one/epg/epg_lite.xml.gz"},
        {"FreeEPG","https://free-epg.de/api/epg/ru.xml.gz"}
    };

    public static final class Show {
        public final String title;
        public final long start,end;
        public Show(String title,long start,long end) {
            this.title=title;this.start=start;this.end=end;
        }
    }
    public static final class Slot {
        public Show now,next;
        public final List<Show> schedule=new ArrayList<>();
    }
    public static final class LoadResult {
        public final Map<String,Slot> data;
        public final String source,diagnostic;
        public final int matched;
        public LoadResult(Map<String,Slot> data,String source,String diagnostic,int matched) {
            this.data=data;this.source=source;this.diagnostic=diagnostic;this.matched=matched;
        }
    }

    private final Map<String,Slot> guide=new HashMap<>();
    private String source=""; 
    private String diagnostic="Не удалось загрузить программу передач";
    private boolean loaded=false;

    public synchronized boolean hasLoaded() {return loaded;}
    public synchronized String status() {return diagnostic;}
    public synchronized String source() {return source;}

    public synchronized Slot forChannel(Channel c) {
        if(c==null)return null;
        Slot slot=guide.get(c.key());
        if(slot==null)return null;
        updateNow(slot);
        return slot;
    }

    public synchronized void setFrom(LoadResult loadedGuide) {
        guide.clear();
        guide.putAll(loadedGuide.data);
        source=loadedGuide.source;
        diagnostic=loadedGuide.diagnostic;
        loaded=true;
    }

    public static LoadResult fetch(Context context,List<Channel> channels) {
        Map<String,String> aliases=aliases(channels);
        Map<String,Slot> merged=new HashMap<>();
        String sourceUsed="",issues="";
        int total=channels.size();
        for(int index=0;index<SOURCES.length;index++) {
            String name=SOURCES[index][0];
            try {
                File file=download(context,index,SOURCES[index][1]);
                Map<String,Slot> available=parse(file,aliases);
                for(Map.Entry<String,Slot> entry:available.entrySet()) {
                    if(!merged.containsKey(entry.getKey()) || merged.get(entry.getKey()).schedule.isEmpty())
                        merged.put(entry.getKey(),entry.getValue());
                }
                if(!available.isEmpty()) {
                    if(sourceUsed.isEmpty())sourceUsed=name;
                    else sourceUsed+=", "+name;
                }
                if(merged.size()>=Math.max(5,(total*8)/10))break;
            } catch(Exception ex) {
                issues+=name+": "+ex.getClass().getSimpleName()+"; ";
            }
        }
        String status;
        if(merged.isEmpty())
            status="Для выбранных каналов нет актуальной программы. "+issues;
        else
            status="Телепрограмма: "+merged.size()+"/"+total+" каналов. Источники: "+sourceUsed;
        return new LoadResult(merged,sourceUsed,status,merged.size());
    }

    private static File download(Context ctx,int index,String address) throws Exception {
        File cache=new File(ctx.getFilesDir(),"epg_ru_v23_"+index+".xml.gz");
        if(cache.isFile() && cache.length()>100 &&
           System.currentTimeMillis()-cache.lastModified()<CACHE_MS) return cache;

        File tmp=new File(ctx.getFilesDir(),"epg_ru_v23_"+index+".tmp");
        HttpURLConnection connection=null;
        try {
            connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(16000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent","RF-TV/2.4 (Android TV EPG)");
            connection.setRequestProperty("Accept-Encoding","identity");
            if(connection.getResponseCode()!=200) throw new Exception("HTTP "+connection.getResponseCode());
            int length=0;
            try(InputStream input=connection.getInputStream();
                BufferedOutputStream out=new BufferedOutputStream(new FileOutputStream(tmp))) {
                byte[] buffer=new byte[16384];int n;
                while((n=input.read(buffer))!=-1) {
                    length+=n;
                    if(length>MAX_BYTES) throw new Exception("EPG over size limit");
                    out.write(buffer,0,n);
                }
            }
            if(length<100)throw new Exception("Empty XMLTV");
            if(cache.isFile()&&!cache.delete())throw new Exception("Old cache locked");
            if(!tmp.renameTo(cache))throw new Exception("Cannot save EPG");
            return cache;
        } catch(Exception e) {
            tmp.delete();
            if(cache.isFile() && cache.length()>100) return cache;
            throw e;
        } finally {if(connection!=null)connection.disconnect();}
    }

    private static Map<String,Slot> parse(File file,Map<String,String> aliases) throws Exception {
        Map<String,String> channelIds=new HashMap<>();
        Map<String,Slot> result=new HashMap<>();
        XmlPullParser parser=Xml.newPullParser();
        long now=System.currentTimeMillis();
        try(BufferedInputStream raw=new BufferedInputStream(new FileInputStream(file))) {
            raw.mark(3);
            boolean compressed=raw.read()==0x1f && raw.read()==0x8b;
            raw.reset();
            InputStream input=compressed?new GZIPInputStream(raw,32768):raw;
            parser.setInput(input,"UTF-8");
            String channelId=null,matchedChannel=null,progId=null,title=null;
            long start=0,end=0;
            int event=parser.getEventType(),programmeCount=0;
            while(event!=XmlPullParser.END_DOCUMENT) {
                if(event==XmlPullParser.START_TAG) {
                    String tag=parser.getName();
                    if("channel".equals(tag)) {
                        channelId=parser.getAttributeValue(null,"id");
                        matchedChannel=findChannel(aliases,channelId);
                    } else if("display-name".equals(tag) && channelId!=null) {
                        String name=parser.nextText();
                        if(matchedChannel==null) matchedChannel=findChannel(aliases,name);
                    } else if("programme".equals(tag)) {
                        progId=parser.getAttributeValue(null,"channel");
                        start=parseTime(parser.getAttributeValue(null,"start"));
                        end=parseTime(parser.getAttributeValue(null,"stop"));
                        title=null;
                    } else if("title".equals(tag) && progId!=null && title==null) {
                        title=parser.nextText();
                    }
                } else if(event==XmlPullParser.END_TAG) {
                    String tag=parser.getName();
                    if("channel".equals(tag)) {
                        if(channelId!=null && matchedChannel!=null)channelIds.put(channelId,matchedChannel);
                        channelId=null;matchedChannel=null;
                    } else if("programme".equals(tag)) {
                        if(progId!=null && title!=null && !title.trim().isEmpty() &&
                            end>now-HOUR && start<now+12*HOUR && end>start) {
                            String channel=channelIds.get(progId);
                            if(channel==null) channel=findChannel(aliases,progId);
                            if(channel!=null) {
                                Slot slot=result.get(channel);
                                if(slot==null){slot=new Slot();result.put(channel,slot);}
                                if(slot.schedule.size()<150)
                                    slot.schedule.add(new Show(title.trim(),start,end));
                            }
                        }
                        progId=null;title=null;
                        if(++programmeCount>500000)break;
                    }
                }
                event=parser.next();
            }
        }
        // A feed must contain contemporary programmes, not only obsolete channel labels.
        result.entrySet().removeIf(e->e.getValue().schedule.isEmpty());
        for(Slot slot:result.values()){
            slot.schedule.sort((a,b)->Long.compare(a.start,b.start));
            updateNow(slot);
        }
        return result;
    }

    private static void updateNow(Slot slot) {
        long now=System.currentTimeMillis();
        slot.now=null;slot.next=null;
        for(Show show:slot.schedule) {
            if(show.start<=now && show.end>now)slot.now=show;
            else if(show.start>now && (slot.next==null || show.start<slot.next.start))
                slot.next=show;
        }
    }

    public static String time(long millis) {
        return new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(millis));
    }

    private static long parseTime(String input) {
        if(input==null)return 0L;
        try {
            String value=input.trim().replaceAll("\\s+"," ");
            if(value.matches("\\d{14}\\s?[+-]\\d{4}")) {
                value=value.replaceFirst("(?<=\\d{14})(?=[+-])"," ");
                SimpleDateFormat d=new SimpleDateFormat("yyyyMMddHHmmss Z",Locale.US);
                d.setLenient(false);return d.parse(value).getTime();
            }
            if(value.matches("\\d{12}\\s?[+-]\\d{4}")) {
                value=value.replaceFirst("(?<=\\d{12})(?=[+-])"," ");
                return new SimpleDateFormat("yyyyMMddHHmm Z",Locale.US).parse(value).getTime();
            }
            if(value.matches("\\d{14}")) {
                SimpleDateFormat d=new SimpleDateFormat("yyyyMMddHHmmss",Locale.US);
                d.setTimeZone(TimeZone.getTimeZone("Europe/Moscow"));
                return d.parse(value).getTime();
            }
        } catch(Exception ignored){}
        return 0L;
    }

    private static String findChannel(Map<String,String> aliases,String input) {
        if(input==null)return null;
        String candidate=normalized(input);
        String key=aliases.get(candidate);
        if(key!=null)return key;
        int at=input.indexOf('@');
        if(at>0)return aliases.get(normalized(input.substring(0,at)));
        return null;
    }

    private static String normalized(String name) {
        if(name==null)return "";
        return name.toLowerCase(Locale.ROOT).replace('ё','е')
            .replaceAll("\\s*\\((?:hd|sd|fhd)\\)","")
            .replaceAll("\\s+(?:hd|sd|fhd)$","")
            .replaceAll("[^a-zа-я0-9]","");
    }

    private static Map<String,String> aliases(List<Channel> channels) {
        Map<String,String> match=new HashMap<>();
        for(Channel c:channels) {
            String id=c.key();
            add(match,id,c.id,c.name,c.id.replaceAll("(?i)\\.ru$",""));
            switch(c.id) {
                case "ChannelOne.ru": add(match,id,"Первый","1 канал","Первый канал HD","1tv","Channel One","1TV.ru");break;
                case "Russia1.ru": add(match,id,"Россия-1","Россия HD","Россия","РТР","Rossiya 1","Russia 1");break;
                case "NTV.ru": add(match,id,"НТВ HD","NTV","NTV HD");break;
                case "RENTV.ru": add(match,id,"Рен ТВ","РЕН","REN TV","RenTV");break;
                case "5Channel.ru": add(match,id,"5 канал","Пятый","5ТВ");break;
                case "TNT.ru": add(match,id,"ТНТ HD","TNT");break;
                case "STS.ru": add(match,id,"СТС HD","CTC","STS");break;
                case "TVCentr.ru": add(match,id,"ТВЦ","ТВ Центр","ТВ Центр HD","TVC","TV Centre");break;
                case "Russia24.ru": add(match,id,"Россия-24","Вести 24","Russia 24");break;
                case "RussiaK.ru": add(match,id,"Культура","Россия Культура","Россия-К","Russia K");break;
                case "Carousel.ru": add(match,id,"Карусель","Karusel");break;
                case "Friday.ru": add(match,id,"Пятница","Пятница!","Friday!");break;
                case "Zvezda.ru": add(match,id,"Звезда","ТК Звезда");break;
                case "Mir.ru": add(match,id,"Мир","МИР HD","MIR");break;
                case "Domashniy.ru": add(match,id,"Домашний","Домашний HD","Domashniy");break;
                case "TV3.ru": add(match,id,"ТВ3","ТВ-3","TV3");break;
                case "OTR.ru": add(match,id,"ОТР","Общественное телевидение России");break;
                case "RBKTV.ru": add(match,id,"РБК ТВ","РБК","РБК-ТВ","RBK");break;
                case "MuzTV.ru": add(match,id,"Муз ТВ","МУЗ-ТВ","Muz TV");break;
                case "MatchTV.ru": add(match,id,"Матч!","Матч ТВ","Матч! ТВ","Match TV");break;
                case "Domkino.ru": add(match,id,"Дом Кино","Дом кино");break;
                case "Solnce.ru": add(match,id,"Солнце","Solntse");break;
                case "Mult.ru": add(match,id,"Мульт","МУЛЬТ HD");break;
                case "STSLove.ru": add(match,id,"СТС Love","CTC Love","STS Love");break;
                case "TNT4.ru": add(match,id,"ТНТ4","ТНТ 4","TNT4");break;
                case "Che.ru": add(match,id,"Че","Че!","Che");break;
                case "2x2.ru": add(match,id,"2x2","2×2","Дважды два");break;
                case "U.ru": add(match,id,"Ю","Телеканал Ю","U TV");break;
                case "Spas.ru": add(match,id,"Спас","СПАС");break;
                case "360.ru": add(match,id,"360","360°","Подмосковье 360");break;
                case "MosfilmGoldCollection.ru": add(match,id,"Мосфильм. Золотая коллекция","Мосфильм Золотая коллекция");break;
                default: break;
            }
        }
        return match;
    }
    private static void add(Map<String,String> m,String target,String... names) {
        for(String name:names)if(name!=null&&!name.isEmpty())m.put(normalized(name),target);
    }
}
