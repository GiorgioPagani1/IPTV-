package ru.reelbot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.Gravity;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VIDEO_REQUEST=1001, AUDIO_REQUEST=1002;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<Source> sources=new ArrayList<>();
    private Uri music, saved;
    private Button chooseVideos, chooseMusic, start, share, cancel;
    private TextView detail, message;
    private ProgressBar progress;
    private VideoView preview;
    private RadioGroup length, rhythm;
    private EditText instructionsField;
    private final ArrayList<Button> presetButtons=new ArrayList<>();
    private Transformer transformer;
    private Runnable ticker;
    private File temporary;
    private int generation=0;
    private boolean busy=false;

    static final class Source {
        final Uri uri;
        final long duration;
        Source(Uri uri,long duration) { this.uri=uri;this.duration=duration; }
    }
    static final class Shot {
        final int source;
        final long from,to;
        final double score;
        final float[] entry, exit;
        final double dx,dy;
        Shot(int source,long from,long to,double score) {
            this(source,from,to,score,null,null,0,0);
        }
        Shot(int source,long from,long to,double score,float[] entry,float[] exit,double dx,double dy) {
            this.source=source;this.from=from;this.to=to;this.score=score;
            this.entry=entry;this.exit=exit;this.dx=dx;this.dy=dy;
        }
        Shot trimmed(long milliseconds) {
            return new Shot(source,from,from+milliseconds,score,entry,exit,dx,dy);
        }
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(16,18,28));
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16,18,28));
        LinearLayout column=new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(20),dp(24),dp(20),dp(24));
        scroll.addView(column);
        addText(column,"REELBOT",32,true,Color.WHITE);
        addText(column,"Загрузи съёмку — получи готовый вертикальный рилс",15,false,0xffb4b9c7);
        addSpace(column,20);
        chooseVideos=button(column,"＋  Добавить отснятые видео");
        detail=addText(column,"Файлы пока не выбраны",14,false,0xffb4b9c7);
        addSpace(column,8);
        chooseMusic=button(column,"♫  Добавить музыку (необязательно)");
        addText(column,"Со своей музыкой оригинальный звук заменяется",12,false,0xff8e96ab);
        addSpace(column,15);
        addText(column,"ДЛИТЕЛЬНОСТЬ",14,true,Color.WHITE);
        length=new RadioGroup(this);
        length.setOrientation(LinearLayout.HORIZONTAL);
        radio(length,"15 сек",15,false);
        radio(length,"30 сек",30,true);
        radio(length,"60 сек",60,false);
        column.addView(length);
        addSpace(column,12);
        addText(column,"РИТМ МОНТАЖА",14,true,Color.WHITE);
        rhythm=new RadioGroup(this);
        rhythm.setOrientation(LinearLayout.HORIZONTAL);
        radio(rhythm,"Динамичный",1,true);
        radio(rhythm,"Спокойный",2,false);
        column.addView(rhythm);
        addSpace(column,18);
        addText(column,"ЗАДАНИЕ ДЛЯ МОНТАЖА",14,true,Color.WHITE);
        addText(column,"Напиши, как монтировать видео. Команды работают локально, без облачного ИИ.",12,false,0xffa8b0c0);
        instructionsField=new EditText(this);
        instructionsField.setTextColor(Color.WHITE);
        instructionsField.setHintTextColor(0xff8491a9);
        instructionsField.setTextSize(14);
        instructionsField.setMinLines(3);
        instructionsField.setMaxLines(6);
        instructionsField.setGravity(Gravity.TOP | Gravity.START);
        instructionsField.setInputType(InputType.TYPE_CLASS_TEXT |
            InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        instructionsField.setHint("Например: Склейки по движению, сохраняй направление. Монтаж в бит музыки, динамичный темп.");
        column.addView(instructionsField,new LinearLayout.LayoutParams(-1,dp(128)));
        addText(column,"БЫСТРЫЕ ЗАДАНИЯ",12,true,0xffbbc6e1);
        preset(column,"↗  По движению","Динамичный монтаж. Подбирай склейки по направлению движения.");
        preset(column,"◎  По объекту","Плавный монтаж. Склейки по объекту — подбирай кадры похожие по цвету, форме и композиции.");
        preset(column,"♫  В музыкальный бит","Динамичный монтаж, делай склейки в бит музыки по сильным ударам.");
        preset(column,"✦  Движение + бит","Динамичные склейки по движению, сохраняй направление и монтируй в бит музыки.");
        addSpace(column,15);
        start=button(column,"✦  АВТОМОНТАЖ ПО ЗАДАНИЮ");
        cancel=button(column,"Остановить");
        cancel.setVisibility(View.GONE);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams=new LinearLayout.LayoutParams(-1,dp(10));
        progressParams.topMargin=dp(12);
        column.addView(progress,progressParams);
        message=addText(column,"Монтаж происходит прямо на телефоне, без загрузки в интернет.",13,false,0xffa8b0c0);
        addSpace(column,12);
        preview=new VideoView(this);
        preview.setVisibility(View.GONE);
        column.addView(preview,new LinearLayout.LayoutParams(-1,dp(300)));
        preview.setOnPreparedListener(MediaPlayer::start);
        share=button(column,"Поделиться видео");
        share.setVisibility(View.GONE);
        addSpace(column,12);
        addText(column,"Ритм определяется по звуковым ударам в выбранной музыке. Склейки по объекту сравнивают цвет/композицию, а по движению — примерное направление. Распознавания предметов и полноценного понимания сюжета пока нет.",12,false,0xff8e96ab);
        setContentView(scroll);
        chooseVideos.setOnClickListener(v->picker("video/*",true,VIDEO_REQUEST));
        chooseMusic.setOnClickListener(v->picker("audio/*",false,AUDIO_REQUEST));
        start.setOnClickListener(v->assemble());
        cancel.setOnClickListener(v->cancelJob());
        share.setOnClickListener(v->{
            if(saved==null)return;
            Intent intent=new Intent(Intent.ACTION_SEND);
            intent.setType("video/mp4");
            intent.putExtra(Intent.EXTRA_STREAM,saved);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent,"Поделиться рилсом"));
        });
    }

    private TextView addText(LinearLayout parent,String value,int sp,boolean bold,int color) {
        TextView t=new TextView(this); t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(null,1);
        t.setPadding(0,dp(4),0,dp(4));parent.addView(t);return t;
    }
    private void addSpace(LinearLayout p,int height) {
        View spacer=new View(this);p.addView(spacer,new LinearLayout.LayoutParams(1,dp(height)));
    }
    private Button button(LinearLayout p,String label) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);
        p.addView(b,new LinearLayout.LayoutParams(-1,dp(52)));return b;
    }
    private void preset(LinearLayout parent,String label,String text) {
        Button b=button(parent,label);
        b.setOnClickListener(v->{
            instructionsField.setText(text);
            instructionsField.setSelection(instructionsField.getText().length());
        });
        presetButtons.add(b);
    }
    private void radio(RadioGroup p,String label,int id,boolean checked) {
        RadioButton b=new RadioButton(this);b.setId(id);b.setText(label);
        b.setTextColor(Color.WHITE);b.setTextSize(13);b.setPadding(0,0,dp(11),0);
        p.addView(b);b.setChecked(checked);
    }
    private int dp(int dp){return (int)(dp*getResources().getDisplayMetrics().density+.5f);}

    @SuppressWarnings("deprecation")
    private void picker(String type,boolean multi,int request) {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType(type);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,multi);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,request);
    }
    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(result!=RESULT_OK||data==null||busy)return;
        if(code==AUDIO_REQUEST) {
            music=data.getData();
            if(music!=null){retain(music);chooseMusic.setText("♫ Музыка выбрана (нажми для замены)");}
        } else if(code==VIDEO_REQUEST) {
            LinkedHashSet<Uri> items=new LinkedHashSet<>();
            if(data.getData()!=null)items.add(data.getData());
            ClipData clips=data.getClipData();
            if(clips!=null)for(int n=0;n<clips.getItemCount();n++)items.add(clips.getItemAt(n).getUri());
            for(Source s:sources)items.remove(s.uri);
            List<Uri> incoming=new ArrayList<>();
            for(Uri u:items) {
                if(incoming.size()+sources.size()>=20)break;
                retain(u);incoming.add(u);
            }
            if(incoming.isEmpty())return;
            setBusy(true,"Читаю видеоклипы...");
            final int job=++generation;
            worker.execute(()->{
                List<Source> loaded=new ArrayList<>();
                for(Uri uri:incoming){
                    MediaMetadataRetriever r=new MediaMetadataRetriever();
                    try {
                        r.setDataSource(this,uri);
                        String metadata=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                        long ms=metadata==null?0:Long.parseLong(metadata);
                        if(ms>=800)loaded.add(new Source(uri,ms));
                    }catch(Exception ignored){}finally{try{r.release();}catch(Exception ignored){}}
                }
                main.post(()->{
                    if(job!=generation||isFinishing())return;
                    sources.addAll(loaded);
                    long sec=0;for(Source s:sources)sec+=s.duration/1000;
                    detail.setText("Загружено: "+sources.size()+" видео, всего "+(sec/60)+" мин "+(sec%60)+" сек");
                    setBusy(false,loaded.size()+" файлов добавлено");
                });
            });
        }
    }
    private void retain(Uri u) {
        try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(SecurityException ignored){}
    }

    private void assemble() {
        if(sources.isEmpty()){message.setText("Сначала выбери хотя бы один видеоклип");return;}
        final ArrayList<Source> selected=new ArrayList<>(sources);
        final int lengthSeconds=length.getCheckedRadioButtonId();
        final Uri soundtrack=music;
        final int defaultSegmentMs=rhythm.getCheckedRadioButtonId()==1?2800:4800;
        final EditInstructions edit=EditInstructions.parse(
            instructionsField.getText().toString(),defaultSegmentMs);
        if(edit.toBeat && soundtrack==null) {
            message.setText("Для монтажа в бит добавь музыку через кнопку ♫");
            return;
        }
        final int job=++generation;
        saved=null;share.setVisibility(View.GONE);preview.stopPlayback();preview.setVisibility(View.GONE);
        setBusy(true,"Инструкция: "+edit.description());
        worker.execute(()->{
            try {
                BeatAnalyzer.Result beats=null;
                if(edit.toBeat) {
                    main.post(()->{if(job==generation)message.setText("Слушаю ритм выбранной музыки...");});
                    beats=BeatAnalyzer.analyze(this,soundtrack,lengthSeconds*1000L);
                }
                if(job!=generation)return;
                List<Long> durations=edit.durations(lengthSeconds*1000L,
                    beats==null?Collections.emptyList():beats.beatsMs);
                String beatNote=beats==null?"":beats.warning==null
                    ?" · ритм ~"+beats.estimatedBpm+" BPM"
                    :" · бит не найден: "+beats.warning+"; равномерная нарезка";
                main.post(()->{if(job==generation)message.setText("Подбираю гармоничные склейки...");});
                List<Shot> shots=plan(selected,edit.segmentMs,lengthSeconds,job,edit,durations);
                main.post(()->{
                    if(job!=generation||isFinishing())return;
                    if(shots.isEmpty()){setBusy(false,"Нет подходящих фрагментов");return;}
                    export(selected,shots,soundtrack,job,beatNote);
                });
            } catch(Exception e) {main.post(()->{
                if(job==generation)setBusy(false,"Ошибка анализа: "+e.getMessage());
            });}
        });
    }
    private List<Shot> plan(List<Source> clips,int cutMs,int targetSec,int job) {
        List<List<Shot>> bySource=new ArrayList<>();
        for(int i=0;i<clips.size();i++) {
            if(job!=generation)break;
            Source s=clips.get(i);ArrayList<Shot> pool=new ArrayList<>();
            long size=Math.min(s.duration,cutMs);
            if(size<800){bySource.add(pool);continue;}
            long last=s.duration-size;
            int num=Math.max(1,Math.min(24,(int)(s.duration/cutMs)));
            MediaMetadataRetriever r=new MediaMetadataRetriever();
            try {
                r.setDataSource(this,s.uri);
                for(int n=0;n<num&&job==generation;n++) {
                    long from=num==1?0:Math.round(last*(n/(double)(num-1)));
                    Bitmap a=sample(r,from+size/3);
                    Bitmap b=sample(r,from+size*2/3);
                    double score=quality(a,b);
                    if(a!=null)a.recycle();
                    if(b!=null)b.recycle();
                    pool.add(new Shot(i,from,from+size,score));
                }
            }catch(Exception ignored) {
                if(pool.isEmpty())pool.add(new Shot(i,0,size,.3));
            }finally{try{r.release();}catch(Exception ignored){}}
            pool.sort((x,y)->Double.compare(y.score,x.score));
            bySource.add(pool);
            int done=i+1,total=clips.size();
            main.post(()->{if(job==generation)message.setText("Анализ: "+done+" из "+total+" видео");});
        }
        List<Shot> result=new ArrayList<>();
        int[] at=new int[clips.size()];
        long remain=targetSec*1000L;
        boolean any=true;
        while(any&&remain>=800&&job==generation) {
            any=false;
            for(int i=0;i<clips.size()&&remain>=800;i++) {
                List<Shot> pool=bySource.get(i);
                while(at[i]<pool.size()) {
                    Shot shot=pool.get(at[i]++);
                    boolean overlap=false;
                    for(Shot prev:result)if(prev.source==i&&shot.from<prev.to&&prev.from<shot.to){overlap=true;break;}
                    if(overlap)continue;
                    long take=Math.min(remain,shot.to-shot.from);
                    if(take<800)continue;
                    result.add(new Shot(i,shot.from,shot.from+take,shot.score));
                    remain-=take;any=true;break;
                }
            }
        }
        return result;
    }
    private Bitmap sample(MediaMetadataRetriever r,long ms) {
        try{return r.getScaledFrameAtTime(ms*1000,MediaMetadataRetriever.OPTION_CLOSEST,64,64);}
        catch(Exception ignored){return null;}
    }
    private double quality(Bitmap a,Bitmap b) {
        if(a==null)return .25;
        double light=0,edge=0,motion=0;int count=0;
        for(int y=2;y<62;y+=3)for(int x=2;x<62;x+=3){
            int c=a.getPixel(x,y),e=a.getPixel(x+2,y);
            int l=(Color.red(c)*54+Color.green(c)*183+Color.blue(c)*19)>>8;
            int q=(Color.red(e)*54+Color.green(e)*183+Color.blue(e)*19)>>8;
            light+=l;edge+=Math.abs(l-q);
            if(b!=null){
                int d=b.getPixel(x,y);
                int ld=(Color.red(d)*54+Color.green(d)*183+Color.blue(d)*19)>>8;
                motion+=Math.abs(l-ld);
            }
            count++;
        }
        double exposure=1-Math.min(1,Math.abs(light/count/255.-.48)/.48);
        return .2*exposure+.3*Math.min(1,edge/count/38.)+.5*Math.min(1,motion/count/50.);
    }

    private void export(List<Source> sources,List<Shot> shots,Uri soundtrack,int job,String beatNote) {
        ArrayList<EditedMediaItem> items=new ArrayList<>();
        for(Shot s:shots){
            MediaItem m=new MediaItem.Builder().setUri(sources.get(s.source).uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(s.from).setEndPositionMs(s.to).build()).build();
            items.add(new EditedMediaItem.Builder(m).setRemoveAudio(soundtrack!=null).build());
        }
        List<EditedMediaItemSequence> tracks=new ArrayList<>();
        tracks.add(soundtrack==null?
            EditedMediaItemSequence.withAudioAndVideoFrom(items):
            EditedMediaItemSequence.withVideoFrom(items));
        if(soundtrack!=null){
            EditedMediaItem sound=new EditedMediaItem.Builder(MediaItem.fromUri(soundtrack)).build();
            tracks.add(EditedMediaItemSequence.withAudioFrom(Collections.singletonList(sound))
                .buildUpon().setIsLooping(true).build());
        }
        Composition mix=new Composition.Builder(tracks)
            .setEffects(new Effects(Collections.emptyList(),Collections.singletonList(
                Presentation.createForWidthAndHeight(1080,1920,
                    Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP)))).build();
        temporary=new File(getCacheDir(),"ReelBot_"+System.currentTimeMillis()+".mp4");
        try {
            transformer=new Transformer.Builder(this)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition c,ExportResult result){
                        stopTicker();transformer=null;
                        if(job!=generation)return;
                        message.setText("Сохраняю в галерею...");
                        File file=temporary;
                        worker.execute(()->{
                            try {
                                Uri uri=saveToGallery(file);
                                file.delete();
                                main.post(()->{
                                    if(job!=generation)return;
                                    saved=uri;setBusy(false,"Готово! Рилс в галерее → Movies/ReelBot"+beatNote);
                                    preview.setVisibility(View.VISIBLE);
                                    preview.setVideoURI(uri);preview.requestFocus();
                                    share.setVisibility(View.VISIBLE);
                                });
                            }catch(Exception e){file.delete();main.post(()->{
                                if(job==generation)setBusy(false,"Ошибка сохранения: "+e.getMessage());
                            });}
                        });
                    }
                    @Override public void onError(Composition c,ExportResult result,ExportException err){
                        stopTicker();transformer=null;
                        if(temporary!=null)temporary.delete();
                        if(job==generation)setBusy(false,"Не удалось собрать MP4: "+err.getMessage());
                    }
                }).build();
            transformer.start(mix,temporary.getAbsolutePath());
            ProgressHolder holder=new ProgressHolder();
            ticker=new Runnable(){
                @Override public void run(){
                    if(transformer==null||job!=generation)return;
                    if(transformer.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE){
                        progress.setIndeterminate(false);progress.setProgress(holder.progress);
                        message.setText("Монтаж... "+holder.progress+"%");
                    }
                    main.postDelayed(this,500);
                }
            };
            main.postDelayed(ticker,500);
        }catch(Exception e){
            transformer=null;stopTicker();setBusy(false,"Ошибка экспорта: "+e.getMessage());
        }
    }

    private Uri saveToGallery(File video) throws Exception {
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,video.getName());
        values.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,"Movies/ReelBot");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri output=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
        if(output==null)throw new Exception("MediaStore недоступен");
        boolean success=false;
        try {
            try(FileInputStream input=new FileInputStream(video);
                OutputStream out=getContentResolver().openOutputStream(output)) {
                if(out==null)throw new Exception("Нет доступа к галерее");
                byte[] bytes=new byte[65536];int n;
                while((n=input.read(bytes))!=-1)out.write(bytes,0,n);
            }
            values.clear();values.put(MediaStore.MediaColumns.IS_PENDING,0);
            getContentResolver().update(output,values,null,null);
            success=true;return output;
        }finally{if(!success)getContentResolver().delete(output,null,null);}
    }
    private void cancelJob(){
        generation++;
        stopTicker();
        if(transformer!=null){transformer.cancel();transformer=null;}
        if(temporary!=null)temporary.delete();
        setBusy(false,"Монтаж отменён");
    }
    private void stopTicker(){if(ticker!=null){main.removeCallbacks(ticker);ticker=null;}}
    private void setBusy(boolean active,String status){
        busy=active;message.setText(status);
        chooseVideos.setEnabled(!active);chooseMusic.setEnabled(!active);start.setEnabled(!active);
        if(instructionsField!=null)instructionsField.setEnabled(!active);
        for(Button preset:presetButtons)preset.setEnabled(!active);
        length.setEnabled(!active);rhythm.setEnabled(!active);
        for(int i=0;i<length.getChildCount();i++)length.getChildAt(i).setEnabled(!active);
        for(int i=0;i<rhythm.getChildCount();i++)rhythm.getChildAt(i).setEnabled(!active);
        cancel.setVisibility(active?View.VISIBLE:View.GONE);
        progress.setVisibility(active?View.VISIBLE:View.GONE);
        progress.setIndeterminate(true);
    }
    @Override protected void onDestroy(){
        cancelJob();worker.shutdownNow();super.onDestroy();
    }
}
