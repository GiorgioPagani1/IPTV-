package ru.rftv.app;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Full-screen, on-demand programme guide. The playing video remains behind
 * this translucent surface. No pause/seek controls: RF TV is live-only.
 */
public final class GuidePanel extends FrameLayout {
    public interface Listener {
        void onPlay(Channel channel);
        void onClose();
        void onRefresh();
    }

    private static final int WHITE=Color.WHITE;
    private static final int MUTED=0xff9caec3;
    private static final int BLUE=0xff225ad0;
    private static final int BG=0xed0b1322;
    private static final int ROW_DP=78;
    private static final int HOURS=6;
    private static final int HOUR_DP=190;

    private final Activity activity;
    private final Listener listener;
    private final List<Channel> channels=new ArrayList<>();
    private final LinearLayout body;
    private final TextView nowTab, gridTab;
    private TvGuide tvGuide;
    private int selectedIndex=0;
    private boolean gridMode=false;

    public GuidePanel(Activity activity, List<Channel> channels, TvGuide guide,
                      int currentIndex, Listener listener) {
        super(activity);
        this.activity=activity;
        this.listener=listener;
        this.channels.addAll(channels);
        this.tvGuide=guide;
        if(currentIndex>=0 && currentIndex<channels.size()) selectedIndex=currentIndex;
        setBackgroundColor(BG);
        setFocusable(true);
        setClickable(true);

        LinearLayout layout=new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        addView(layout,new LayoutParams(-1,-1));

        LinearLayout toolbar=new LinearLayout(activity);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(14),dp(12),dp(14),dp(10));
        toolbar.setBackgroundColor(0xc80b1525);

        TextView close=button("‹  Назад",16);
        close.setOnClickListener(v->listener.onClose());
        toolbar.addView(close,new LinearLayout.LayoutParams(dp(116),dp(48)));

        TextView title=text("ТЕЛЕПРОГРАММА",19,WHITE,true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tparams=new LinearLayout.LayoutParams(0,-2,1);
        tparams.leftMargin=dp(10);
        toolbar.addView(title,tparams);

        TextView reload=button("↻",25);
        reload.setContentDescription("Обновить телепрограмму из интернета");
        reload.setOnClickListener(v->{
            listener.onRefresh();
            reload.setText("…");
            reload.postDelayed(()->reload.setText("↻"),3500);
        });
        toolbar.addView(reload,new LinearLayout.LayoutParams(dp(44),dp(45)));
        TextView time=text(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()),
            17,MUTED,true);
        time.setGravity(Gravity.CENTER);
        toolbar.addView(time,new LinearLayout.LayoutParams(dp(60),dp(44)));
        layout.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout tabs=new LinearLayout(activity);
        tabs.setGravity(Gravity.CENTER);
        tabs.setPadding(dp(14),dp(4),dp(14),dp(9));
        nowTab=button("СЕЙЧАС И ДАЛЕЕ",14);
        gridTab=button("СЕТКА ПО ВРЕМЕНИ",14);
        nowTab.setOnClickListener(v->{gridMode=false;render();});
        gridTab.setOnClickListener(v->{gridMode=true;render();});
        LinearLayout.LayoutParams tab=new LinearLayout.LayoutParams(0,dp(48),1);
        tab.rightMargin=dp(5);
        tabs.addView(nowTab,tab);
        LinearLayout.LayoutParams tab2=new LinearLayout.LayoutParams(0,dp(48),1);
        tab2.leftMargin=dp(5);
        tabs.addView(gridTab,tab2);
        layout.addView(tabs,new LinearLayout.LayoutParams(-1,-2));

        body=new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        layout.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        render();
    }

    public void updateGuide(TvGuide guide) {
        tvGuide=guide;
        render();
    }

    @Override public boolean onKeyDown(int keyCode,android.view.KeyEvent event) {
        if(keyCode==android.view.KeyEvent.KEYCODE_BACK) {
            listener.onClose();
            return true;
        }
        return super.onKeyDown(keyCode,event);
    }

    private void render() {
        body.removeAllViews();
        nowTab.setBackground(rounded(gridMode?0xff1c293c:0xff244e96,12));
        gridTab.setBackground(rounded(gridMode?0xff244e96:0xff1c293c,12));
        nowTab.setTextColor(gridMode?MUTED:WHITE);
        gridTab.setTextColor(gridMode?WHITE:MUTED);
        if(channels.isEmpty()) {
            TextView empty=text("Список каналов пуст",18,MUTED,false);
            empty.setGravity(Gravity.CENTER);
            body.addView(empty,new LinearLayout.LayoutParams(-1,-1));
            return;
        }
        if(gridMode) drawGrid();
        else drawChannelGuide();
    }

    private void drawChannelGuide() {
        boolean landscape=activity.getResources().getConfiguration().orientation==
            Configuration.ORIENTATION_LANDSCAPE;
        if(!landscape) {
            LinearLayout outer=new LinearLayout(activity);
            outer.setOrientation(LinearLayout.VERTICAL);
            body.addView(outer,new LinearLayout.LayoutParams(-1,-1));
            HorizontalScrollView strip=new HorizontalScrollView(activity);
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout chips=new LinearLayout(activity);
            chips.setPadding(dp(12),dp(6),dp(12),dp(6));
            for(int i=0;i<channels.size();i++) {
                final int index=i;
                Channel c=channels.get(i);
                TextView chip=button(c.name,14);
                chip.setBackground(rounded(i==selectedIndex?BLUE:0xff1b2b41,10));
                chip.setOnClickListener(v->{selectedIndex=index;render();});
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(120),dp(55));
                cp.rightMargin=dp(7);
                chips.addView(chip,cp);
            }
            strip.addView(chips);
            outer.addView(strip,new LinearLayout.LayoutParams(-1,-2));
            ScrollView detailsScroll=new ScrollView(activity);
            LinearLayout detail=new LinearLayout(activity);
            detail.setOrientation(LinearLayout.VERTICAL);
            detail.setPadding(dp(20),dp(22),dp(20),dp(20));
            fillDetails(detail);
            detailsScroll.addView(detail);
            outer.addView(detailsScroll,new LinearLayout.LayoutParams(-1,0,1));
        } else {
            LinearLayout row=new LinearLayout(activity);
            body.addView(row,new LinearLayout.LayoutParams(-1,-1));
            ScrollView listScroll=new ScrollView(activity);
            listScroll.setFillViewport(false);
            listScroll.setVerticalScrollBarEnabled(false);
            LinearLayout list=new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(dp(12),dp(5),dp(6),dp(10));
            for(int i=0;i<channels.size();i++) {
                final int index=i;
                Channel c=channels.get(i);
                TextView item=button(String.format(Locale.getDefault(),"%02d   %s",i+1,c.name),15);
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(16),0,dp(7),0);
                item.setSingleLine(true);
                item.setEllipsize(android.text.TextUtils.TruncateAt.END);
                item.setBackground(rounded(i==selectedIndex?0xff285fc2:0xff142238,11));
                item.setOnClickListener(v->{selectedIndex=index;render();});
                item.setOnFocusChangeListener((v,focus)->{
                    if(focus) {selectedIndex=index;fillDetailsInPlace();}
                });
                LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,dp(65));
                ip.bottomMargin=dp(3);
                list.addView(item,ip);
            }
            listScroll.addView(list);
            int width=activity.getResources().getDisplayMetrics().widthPixels;
            row.addView(listScroll,new LinearLayout.LayoutParams(Math.min(width/3,dp(320)),-1));

            ScrollView detailsScroll=new ScrollView(activity);
            LinearLayout details=new LinearLayout(activity);
            details.setId(114004);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setPadding(dp(25),dp(20),dp(26),dp(20));
            fillDetails(details);
            detailsScroll.addView(details);
            row.addView(detailsScroll,new LinearLayout.LayoutParams(0,-1,1));
        }
    }

    private void fillDetailsInPlace() {
        LinearLayout details=findViewById(114004);
        if(details==null)return;
        details.removeAllViews();
        fillDetails(details);
    }

    private void fillDetails(LinearLayout pane) {
        Channel channel=channels.get(selectedIndex);
        TextView name=text(channel.name,24,WHITE,true);
        pane.addView(name);
        TvGuide.Slot slot=tvGuide.forChannel(channel);
        if(slot==null || (slot.now==null && slot.next==null)) {
            TextView missing=text(tvGuide.hasLoaded() ?
                tvGuide.status() :
                "Загружаем российскую телепрограмму…",16,MUTED,false);
            missing.setPadding(0,dp(28),0,dp(25));
            pane.addView(missing);
        } else {
            if(slot.now!=null) {
                section(pane,"СЕЙЧАС",slot.now,true);
            }
            if(slot.next!=null) {
                section(pane,"ДАЛЕЕ",slot.next,false);
            }
        }
        TextView tune=button("▶   Смотреть "+channel.name,16);
        tune.setBackground(rounded(0xff2253a8,12));
        tune.setOnClickListener(v->listener.onPlay(channel));
        LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-1,dp(55));
        tp.topMargin=dp(30);
        pane.addView(tune,tp);
    }

    private void section(LinearLayout parent,String label,TvGuide.Show show,boolean current) {
        TextView section=text(label,12,current?0xff85baff:MUTED,true);
        section.setPadding(0,dp(25),0,dp(7));
        parent.addView(section);
        TextView title=text(show.title,23,WHITE,true);
        title.setMaxLines(3);
        parent.addView(title);
        TextView interval=text(TvGuide.time(show.start)+" — "+TvGuide.time(show.end),16,MUTED,false);
        interval.setPadding(0,dp(7),0,dp(9));
        parent.addView(interval);
        if(current) {
            FrameLayout line=new FrameLayout(activity);
            line.setBackground(rounded(0xff344357,4));
            View progress=new View(activity);
            progress.setBackground(rounded(0xff7fb2ff,4));
            long range=Math.max(1,show.end-show.start);
            float fraction=Math.max(0,Math.min(1,(System.currentTimeMillis()-show.start)/(float)range));
            line.addView(progress,new FrameLayout.LayoutParams(
                Math.max(dp(2),(int)(dp(300)*fraction)),dp(5)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(300),dp(5));
            parent.addView(line,lp);
        }
    }

    private void drawGrid() {
        ScrollView vertical=new ScrollView(activity);
        vertical.setFillViewport(false);
        vertical.setVerticalScrollBarEnabled(true);
        body.addView(vertical,new LinearLayout.LayoutParams(-1,-1));

        int leftWidth=dp(142);
        int rowHeight=dp(ROW_DP);
        int hourWidth=dp(HOUR_DP);
        int topHeight=dp(52);
        int totalWidth=HOURS*hourWidth;
        long now=System.currentTimeMillis();
        long origin=(now/3600000L-1)*3600000L;
        long end=origin+HOURS*3600000L;
        LinearLayout horizontal=new LinearLayout(activity);
        vertical.addView(horizontal,new ScrollView.LayoutParams(-1,-2));

        LinearLayout channelCol=new LinearLayout(activity);
        channelCol.setOrientation(LinearLayout.VERTICAL);
        TextView sideHeader=text("КАНАЛ",12,MUTED,true);
        sideHeader.setGravity(Gravity.CENTER);
        channelCol.addView(sideHeader,new LinearLayout.LayoutParams(-1,topHeight));
        for(int i=0;i<channels.size();i++) {
            Channel c=channels.get(i);
            TextView label=button(c.name,13);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(dp(11),0,dp(4),0);
            label.setBackground(rounded(i==selectedIndex?0xff204b9c:0xff152338,5));
            label.setOnClickListener(v->listener.onPlay(c));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,rowHeight);
            lp.bottomMargin=dp(3);
            channelCol.addView(label,lp);
        }
        horizontal.addView(channelCol,new LinearLayout.LayoutParams(leftWidth,-2));

        HorizontalScrollView hoursScroll=new HorizontalScrollView(activity);
        hoursScroll.setFillViewport(false);
        LinearLayout.LayoutParams scrollParam=new LinearLayout.LayoutParams(0,-2,1);
        horizontal.addView(hoursScroll,scrollParam);
        FrameLayout grid=new FrameLayout(activity);
        grid.setBackgroundColor(0xff0d1725);
        grid.setLayoutParams(new HorizontalScrollView.LayoutParams(totalWidth,
            topHeight+channels.size()*(rowHeight+dp(3))));
        hoursScroll.addView(grid);

        for(int h=0;h<HOURS;h++) {
            final int hi=h;
            TextView tick=text(TvGuide.time(origin+h*3600000L),14,MUTED,false);
            tick.setGravity(Gravity.CENTER_VERTICAL);
            tick.setPadding(dp(8),0,0,0);
            FrameLayout.LayoutParams tl=new FrameLayout.LayoutParams(hourWidth,topHeight);
            tl.leftMargin=h*hourWidth;
            tl.topMargin=0;
            grid.addView(tick,tl);
            View seam=new View(activity);
            seam.setBackgroundColor(0x3b657e9a);
            FrameLayout.LayoutParams seamLp=new FrameLayout.LayoutParams(dp(1),-1);
            seamLp.leftMargin=h*hourWidth;
            grid.addView(seam,seamLp);
        }

        for(int r=0;r<channels.size();r++) {
            Channel channel=channels.get(r);
            int top=topHeight+r*(rowHeight+dp(3));
            TvGuide.Slot slot=tvGuide.forChannel(channel);
            View backdrop=new View(activity);
            backdrop.setBackground(rounded(0xff16243a,5));
            FrameLayout.LayoutParams bgp=new FrameLayout.LayoutParams(totalWidth,rowHeight);
            bgp.topMargin=top;
            grid.addView(backdrop,bgp);
            if(slot==null || slot.schedule.isEmpty()) {
                TextView empty=text(tvGuide.hasLoaded()?"Нет данных":"Загрузка EPG…",13,MUTED,false);
                empty.setGravity(Gravity.CENTER_VERTICAL);
                empty.setPadding(dp(12),0,0,0);
                FrameLayout.LayoutParams ep=new FrameLayout.LayoutParams(dp(230),rowHeight);
                ep.topMargin=top;
                grid.addView(empty,ep);
                continue;
            }
            for(TvGuide.Show show:slot.schedule) {
                long start=Math.max(origin,show.start),stop=Math.min(end,show.end);
                if(stop<=start)continue;
                int left=(int)((start-origin)/(double)3600000L*hourWidth);
                int width=Math.max(dp(24),(int)((stop-start)/(double)3600000L*hourWidth)-dp(3));
                boolean active=show.start<=now && show.end>now;
                TextView block=text(show.title,13,WHITE,active);
                block.setGravity(Gravity.CENTER_VERTICAL);
                block.setMaxLines(2);
                block.setEllipsize(android.text.TextUtils.TruncateAt.END);
                block.setPadding(dp(10),dp(5),dp(6),dp(5));
                block.setBackground(rounded(active?BLUE:0xff23354d,8));
                block.setContentDescription(channel.name+": "+show.title+
                    ", "+TvGuide.time(show.start)+" — "+TvGuide.time(show.end));
                block.setFocusable(true);
                block.setClickable(true);
                block.setOnClickListener(v->listener.onPlay(channel));
                FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(width,rowHeight-dp(5));
                bp.leftMargin=left;
                bp.topMargin=top+dp(2);
                grid.addView(block,bp);
            }
        }
        int x=(int)((now-origin)/(double)3600000L*hourWidth);
        if(x>=0 && x<totalWidth) {
            View nowLine=new View(activity);
            nowLine.setBackgroundColor(0xff84c5ff);
            FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(dp(2),-1);
            np.leftMargin=x;
            grid.addView(nowLine,np);
            hoursScroll.post(()->hoursScroll.scrollTo(Math.max(0,x-dp(220)),0));
        }
    }

    private TextView button(String value,int size) {
        TextView b=text(value,size,WHITE,true);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(rounded(0xff1c293c,10));
        return b;
    }

    private TextView text(String str,int size,int color,boolean bold) {
        TextView result=new TextView(activity);
        result.setText(str);
        result.setTextColor(color);
        result.setTextSize(size);
        result.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        return result;
    }
    private GradientDrawable rounded(int color,float r) {
        GradientDrawable result=new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(r));
        return result;
    }
    private int dp(float value) {
        return Math.round(value*getResources().getDisplayMetrics().density);
    }
}
