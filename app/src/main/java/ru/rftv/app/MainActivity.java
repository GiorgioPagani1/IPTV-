package ru.rftv.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final String SOURCE="https://iptv-org.github.io/iptv/countries/ru.m3u";
    private static final int BG=0xff09101a, PANEL=0xff142237, ACCENT=0xff6fafff;
    private final ArrayList<Channel> channels=new ArrayList<>();
    private final Set<String> favorites=new HashSet<>();
    private ExoPlayer player;
    private SharedPreferences preferences;
    private LinearLayout screen, rows, controls;
    private ScrollView scroll;
    private TextView status, allTab, favoriteTab;
    private FrameLayout videoHolder;
    private int videoHeight, current=-1;
    private boolean favoritesOnly=false, menuVisible=true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences=getSharedPreferences("rf_tv",MODE_PRIVATE);
        favorites.addAll(preferences.getStringSet("favorites",new HashSet<>()));
        buildScreen();
        player=new ExoPlayer.Builder(this).build();
        ((PlayerView)videoHolder.getChildAt(0)).setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if(current<0 || current>=channels.size())return;
                if(state==Player.STATE_BUFFERING)notice("Подключение: "+channels.get(current).name);
                if(state==Player.STATE_READY)notice("● ПРЯМОЙ ЭФИР — "+channels.get(current).name);
            }
            @Override public void onPlayerError(PlaybackException error) {
                notice("Канал не работает или недоступен в вашей стране. Выберите другой.");
            }
        });
        fetchChannels();
    }

    private void buildScreen() {
        int width=getResources().getDisplayMetrics().widthPixels;
        int height=getResources().getDisplayMetrics().heightPixels;
        videoHeight=Math.min(height/2, width*9/16);
        screen=new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setBackgroundColor(BG);
        setContentView(screen);

        LinearLayout header=new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(14),dp(10),dp(12),dp(10));
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand=label("РФ ТВ   ●   РОССИЙСКИЕ КАНАЛЫ",19,Color.WHITE);
        header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView update=button("↻");
        update.setOnClickListener(v->fetchChannels());
        header.addView(update);
        screen.addView(header,new LinearLayout.LayoutParams(-1,-2));

        videoHolder=new FrameLayout(this);
        videoHolder.setBackgroundColor(Color.BLACK);
        PlayerView video=new PlayerView(this);
        video.setUseController(false);
        videoHolder.addView(video,new FrameLayout.LayoutParams(-1,-1));
        screen.addView(videoHolder,new LinearLayout.LayoutParams(-1,videoHeight));

        status=label("Подключаем список каналов…",14,0xffaec3d9);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(12),dp(10),dp(12),dp(10));
        screen.addView(status,new LinearLayout.LayoutParams(-1,-2));

        controls=new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setPadding(dp(12),dp(5),dp(12),dp(8));
        TextView previous=button("◀ НАЗАД");
        TextView toggle=button("☰ КАНАЛЫ");
        TextView next=button("ВПЕРЁД ▶");
        controls.addView(previous,new LinearLayout.LayoutParams(0,dp(49),1));
        controls.addView(toggle,new LinearLayout.LayoutParams(0,dp(49),1));
        controls.addView(next,new LinearLayout.LayoutParams(0,dp(49),1));
        previous.setOnClickListener(v->move(-1));
        toggle.setOnClickListener(v->showMenu(!menuVisible));
        next.setOnClickListener(v->move(1));
        screen.addView(controls,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout tabs=new LinearLayout(this);
        tabs.setPadding(dp(12),dp(3),dp(12),dp(3));
        allTab=button("ВСЕ КАНАЛЫ");
        favoriteTab=button("★ ИЗБРАННОЕ");
        tabs.addView(allTab,new LinearLayout.LayoutParams(0,dp(45),1));
        tabs.addView(favoriteTab,new LinearLayout.LayoutParams(0,dp(45),1));
        allTab.setOnClickListener(v->{ favoritesOnly=false; renderRows(); });
        favoriteTab.setOnClickListener(v->{ favoritesOnly=true; renderRows(); });
        screen.addView(tabs);

        scroll=new ScrollView(this);
        scroll.setFillViewport(false);
        rows=new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(12),dp(2),dp(12),dp(16));
        scroll.addView(rows);
        screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        renderRows();
    }

    private TextView label(String name,int size,int color) {
        TextView view=new TextView(this);
        view.setText(name);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private TextView button(String title) {
        TextView b=label(title,13,Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(8),dp(8),dp(8),dp(8));
        b.setBackground(shape(PANEL));
        b.setOnFocusChangeListener((v,focused)->b.setBackground(shape(focused?ACCENT:PANEL)));
        return b;
    }

    private GradientDrawable shape(int color) {
        GradientDrawable bg=new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(9));
        return bg;
    }

    private int dp(int n) {return Math.round(n*getResources().getDisplayMetrics().density);}

    private void renderRows() {
        if(rows==null)return;
        rows.removeAllViews();
        allTab.setTextColor(favoritesOnly?0xff9cb0c4:ACCENT);
        favoriteTab.setTextColor(favoritesOnly?ACCENT:0xff9cb0c4);
        int shown=0;
        for(int i=0;i<channels.size();i++) {
            Channel c=channels.get(i);
            if(favoritesOnly&&!favorites.contains(c.key()))continue;
            final int index=i;
            LinearLayout line=new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(12),dp(5),dp(6),dp(5));
            line.setBackground(shape(i==current?0xff20405d:PANEL));
            TextView name=label((i+1)+".  "+c.name,15,Color.WHITE);
            name.setPadding(dp(4),dp(11),dp(4),dp(11));
            name.setFocusable(true);
            name.setClickable(true);
            name.setOnClickListener(v->play(index));
            name.setOnLongClickListener(v->{favorite(c);return true;});
            name.setOnFocusChangeListener((v,focused)->name.setTextColor(focused?ACCENT:Color.WHITE));
            line.addView(name,new LinearLayout.LayoutParams(0,-2,1));
            TextView star=button(favorites.contains(c.key())?"★":"☆");
            star.setTextSize(21);
            star.setTextColor(favorites.contains(c.key())?0xffffd36a:0xff9eb4ca);
            star.setOnClickListener(v->favorite(c));
            line.addView(star,new LinearLayout.LayoutParams(dp(50),dp(45)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
            lp.bottomMargin=dp(5);
            rows.addView(line,lp);
            shown++;
        }
        if(shown==0) {
            TextView empty=label(channels.isEmpty()?"Загружаем каналы…":"Здесь пока нет избранных каналов",15,0xffb1c1d4);
            empty.setPadding(dp(12),dp(26),0,0);
            rows.addView(empty);
        }
    }

    private void favorite(Channel channel) {
        if(!favorites.add(channel.key()))favorites.remove(channel.key());
        preferences.edit().putStringSet("favorites",new HashSet<>(favorites)).apply();
        renderRows();
    }

    private void showMenu(boolean show) {
        menuVisible=show;
        scroll.setVisibility(show?View.VISIBLE:View.GONE);
        allTab.setVisibility(show?View.VISIBLE:View.GONE);
        favoriteTab.setVisibility(show?View.VISIBLE:View.GONE);
        screen.findViewById(android.R.id.content).requestLayout();
        videoHolder.setLayoutParams(show?
            new LinearLayout.LayoutParams(-1,videoHeight):
            new LinearLayout.LayoutParams(-1,0,1));
    }

    private void notice(String s) {if(status!=null)status.setText(s);}

    private void play(int index) {
        if(index<0 || index>=channels.size())return;
        current=index;
        Channel c=channels.get(index);
        preferences.edit().putString("last",c.url).apply();
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        player.play();
        notice("Подключение: "+c.name);
        renderRows();
        showMenu(false);
    }

    private void move(int delta) {
        if(channels.isEmpty())return;
        play((current<0?0:(current+delta+channels.size())%channels.size()));
    }

    private void fetchChannels() {
        notice("Загружаем российские каналы…");
        new Thread(()->{
            String content=null;
            boolean usedCache=false;
            try {
                HttpURLConnection conn=(HttpURLConnection)new URL(SOURCE).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent","RF-TV/1.1");
                try {
                    if(conn.getResponseCode()!=200)throw new Exception("HTTP "+conn.getResponseCode());
                    ByteArrayOutputStream buffer=new ByteArrayOutputStream();
                    try(InputStream input=conn.getInputStream()) {
                        byte[] block=new byte[8192]; int len;
                        while((len=input.read(block))!=-1) {
                            buffer.write(block,0,len);
                            if(buffer.size()>2000000)throw new Exception("List too big");
                        }
                    }
                    content=buffer.toString("UTF-8");
                    if(PlaylistParser.parse(content).isEmpty())throw new Exception("Empty channel list");
                    try(FileOutputStream out=openFileOutput("channels.m3u",MODE_PRIVATE)) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                } finally {conn.disconnect();}
            } catch(Exception e) {
                usedCache=true;
                try(FileInputStream in=openFileInput("channels.m3u"); ByteArrayOutputStream data=new ByteArrayOutputStream()) {
                    byte[] block=new byte[8192]; int len;
                    while((len=in.read(block))!=-1)data.write(block,0,len);
                    content=data.toString("UTF-8");
                } catch(Exception ignored){}
            }
            List<Channel> found=PlaylistParser.parse(content);
            found.sort(Comparator.comparing((Channel c)->c.name.toLowerCase(Locale.ROOT)));
            final boolean cached=usedCache;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                String previous=current>=0&&current<channels.size()?
                    channels.get(current).url:preferences.getString("last","");
                channels.clear();
                channels.addAll(found);
                current=-1;
                for(int i=0;i<channels.size();i++)if(channels.get(i).url.equals(previous)){current=i;break;}
                renderRows();
                if(channels.isEmpty())notice("Не удалось загрузить каналы. Проверьте интернет и нажмите ↻.");
                else notice("Каналов: "+channels.size()+(cached?" (из сохранённого списка)":"")+" — выберите канал");
            });
        }).start();
    }

    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if(key==KeyEvent.KEYCODE_CHANNEL_UP||key==KeyEvent.KEYCODE_MEDIA_NEXT){move(1);return true;}
        if(key==KeyEvent.KEYCODE_CHANNEL_DOWN||key==KeyEvent.KEYCODE_MEDIA_PREVIOUS){move(-1);return true;}
        if(!menuVisible&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_MENU)) {
            showMenu(true);return true;
        }
        return super.onKeyDown(key,event);
    }
    @Override public void onBackPressed() {
        if(!menuVisible)showMenu(true);
        else if(current>=0)showMenu(false);
        else super.onBackPressed();
    }
    @Override protected void onStop() {
        super.onStop(); if(player!=null)player.pause();
    }
    @Override protected void onDestroy() {
        if(player!=null)player.release();
        super.onDestroy();
    }
}
