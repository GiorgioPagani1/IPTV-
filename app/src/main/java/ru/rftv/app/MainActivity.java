package ru.rftv.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String SOURCE = "https://iptv-org.github.io/iptv/countries/ru.m3u";
    private static final long AUTO_HIDE_MS = 5000L;
    private static final long STREAM_TIMEOUT_MS = 14000L;
    private static final int WHITE = Color.WHITE, DIM = 0xffb8c4d5, BLUE = 0xff92bfff, YELLOW = 0xffffcf64;
    private final ArrayList<Channel> channels = new ArrayList<>();
    private final ArrayList<Integer> shown = new ArrayList<>();
    private final Set<String> favorites = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService iconThreads = Executors.newFixedThreadPool(2);
    private final LruCache<String,Bitmap> iconCache = new LruCache<>(64);
    private final Runnable hideTask = () -> hideControls();
    private final Runnable streamWatchdog = () -> switchSource("Превышено время ожидания");
    private final ArrayList<String> activeSources = new ArrayList<>();
    private SharedPreferences prefs;
    private FrameLayout root, controls, shade;
    private LinearLayout drawer, header;
    private PlayerView videoView;
    private ExoPlayer player;
    private TextView channelTitle, channelSubtitle, message, favoriteButton, allTab, favTab, countLabel;
    private TextView emptyText;
    private EditText search;
    private ListView channelList;
    private ChannelAdapter adapter;
    private int current = -1, sourceIndex = 0;
    private boolean allSourcesFailed = false;
    private String lastError = "";
    private boolean controlsVisible = true, drawerOpen = false, favoritesOnly = false, loading = false;
    private String query = "";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        prefs = getSharedPreferences("rf_tv", MODE_PRIVATE);
        favorites.addAll(prefs.getStringSet("favorites", new HashSet<>()));
        createUi();
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
            .setUserAgent("RF-TV/2.1 (Android IPTV Player)")
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(12000)
            .setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this)
            .setMediaSourceFactory(new DefaultMediaSourceFactory(this).setDataSourceFactory(http))
            .build();
        videoView.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if(current < 0 || current >= channels.size() || allSourcesFailed) return;
                if(state == Player.STATE_BUFFERING) {
                    setMessage("Проверяем эфир • источник " + (sourceIndex+1) + "/" + activeSources.size());
                    scheduleStreamTimeout();
                }
                if(state == Player.STATE_READY) {
                    handler.removeCallbacks(streamWatchdog);
                    setMessage(null);
                    if(sourceIndex < activeSources.size()) {
                        prefs.edit().putString("good_source_" + channels.get(current).key(),
                            activeSources.get(sourceIndex)).apply();
                    }
                    channelSubtitle.setText("● ПРЯМОЙ ЭФИР   •   " + (sourceIndex+1) + "/" + activeSources.size());
                }
            }
            @Override public void onPlayerError(PlaybackException exception) {
                if(!allSourcesFailed) switchSource(errorDescription(exception));
            }
        });
        loadChannels();
    }

    private void createUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        videoView = new PlayerView(this);
        videoView.setUseController(false);
        videoView.setKeepScreenOn(true);
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1));

        View touchSurface = new View(this);
        touchSurface.setClickable(true);
        touchSurface.setOnClickListener(v -> {
            if(drawerOpen) closeDrawer();
            else if(controlsVisible) hideControls();
            else showControls();
        });
        root.addView(touchSurface, new FrameLayout.LayoutParams(-1, -1));

        message = text("Загружаем российские телеканалы…", 17, WHITE, true);
        message.setGravity(Gravity.CENTER);
        message.setPadding(dp(24), dp(20), dp(24), dp(20));
        message.setBackground(rounded(0xbb0a111b, 16));
        FrameLayout.LayoutParams m = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        m.leftMargin = dp(20);
        m.rightMargin = dp(20);
        root.addView(message, m);

        controls = new FrameLayout(this);
        root.addView(controls, new FrameLayout.LayoutParams(-1, -1));
        createOverlay();

        shade = new FrameLayout(this);
        shade.setVisibility(View.GONE);
        shade.setBackgroundColor(0x99000000);
        shade.setOnClickListener(v -> closeDrawer());
        root.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        createDrawer();
        scheduleHide();
    }

    private void createOverlay() {
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(17), dp(11), dp(19), dp(11));
        titleBox.setBackground(rounded(0x990c1420, 14));
        channelTitle = text("РФ ТВ", 23, WHITE, true);
        channelTitle.setSingleLine(true);
        channelTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        channelSubtitle = text("● ПРЯМОЙ ЭФИР  ·  IPTV", 12, DIM, false);
        titleBox.addView(channelTitle, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams sub = new LinearLayout.LayoutParams(-1, -2);
        sub.topMargin = dp(5);
        titleBox.addView(channelSubtitle, sub);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        titleParams.leftMargin = dp(18);
        titleParams.topMargin = dp(24);
        titleParams.rightMargin = dp(18);
        controls.addView(titleBox, titleParams);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(6));
        bottom.setBackground(rounded(0xbb0c1119, 22));

        TextView previous = overlayButton("◀", "Предыдущий канал");
        TextView menu = overlayButton("☰", "Список каналов");
        favoriteButton = overlayButton("☆", "Добавить в избранное");
        TextView next = overlayButton("▶", "Следующий канал");
        previous.setOnClickListener(v -> move(-1));
        menu.setOnClickListener(v -> openDrawer());
        favoriteButton.setOnClickListener(v -> {
            if(current >= 0 && current < channels.size()) toggleFavorite(channels.get(current));
            showControls();
        });
        next.setOnClickListener(v -> move(1));
        for(TextView b : new TextView[]{previous, menu, favoriteButton, next}) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(62), dp(56));
            bp.leftMargin = dp(4);
            bp.rightMargin = dp(4);
            bottom.addView(b, bp);
        }
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barParams.bottomMargin = dp(22);
        controls.addView(bottom, barParams);
    }

    private TextView overlayButton(String symbol, String description) {
        TextView b = text(symbol, 31, WHITE, false);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription(description);
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackground(rounded(0x001c2738, 14));
        b.setOnFocusChangeListener((v, focused) -> b.setBackground(rounded(focused ? 0xff385677 : 0x001c2738, 14)));
        return b;
    }

    private void createDrawer() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int width = metrics.widthPixels > metrics.heightPixels
            ? Math.min((int)(metrics.widthPixels * .52f), dp(490))
            : Math.min((int)(metrics.widthPixels * .92f), dp(490));

        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xf2101722);
        drawer.setVisibility(View.GONE);
        drawer.setElevation(dp(14));
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(width, -1, Gravity.LEFT);
        root.addView(drawer, drawerParams);

        header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), dp(24), dp(15), dp(14));
        TextView heading = text("Телеканалы", 23, WHITE, true);
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView refresh = text("↻", 25, BLUE, true);
        refresh.setGravity(Gravity.CENTER);
        refresh.setContentDescription("Обновить список каналов");
        refresh.setFocusable(true);
        refresh.setClickable(true);
        refresh.setOnClickListener(v -> { closeDrawer(); loadChannels(); });
        header.addView(refresh, new LinearLayout.LayoutParams(dp(52), dp(44)));
        TextView close = text("×", 33, DIM, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Закрыть список");
        close.setFocusable(true);
        close.setClickable(true);
        close.setOnClickListener(v -> closeDrawer());
        header.addView(close, new LinearLayout.LayoutParams(dp(52), dp(44)));
        drawer.addView(header);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setBackground(rounded(0xff283444, 12));
        searchRow.setPadding(dp(14), dp(1), dp(9), dp(1));
        TextView searchIcon = text("⌕", 27, DIM, false);
        searchRow.addView(searchIcon, new LinearLayout.LayoutParams(dp(34), dp(48)));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextColor(WHITE);
        search.setHintTextColor(0xff9baabc);
        search.setTextSize(16);
        search.setHint("Найти канал");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setBackgroundColor(Color.TRANSPARENT);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(52), 1));
        TextView clear = text("×", 24, DIM, false);
        clear.setGravity(Gravity.CENTER);
        clear.setOnClickListener(v -> search.setText(""));
        clear.setFocusable(true);
        searchRow.addView(clear, new LinearLayout.LayoutParams(dp(38), dp(48)));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, -2);
        searchParams.leftMargin = dp(16);
        searchParams.rightMargin = dp(16);
        drawer.addView(searchRow, searchParams);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filterChannels();
            }
            @Override public void afterTextChanged(Editable e) {}
        });

        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(12), dp(13), dp(12), dp(7));
        tabs.setGravity(Gravity.CENTER_VERTICAL);
        allTab = tab("Все каналы");
        favTab = tab("★ Избранное");
        tabs.addView(allTab, new LinearLayout.LayoutParams(0, dp(46), 1));
        tabs.addView(favTab, new LinearLayout.LayoutParams(0, dp(46), 1));
        allTab.setOnClickListener(v -> { favoritesOnly=false; filterChannels(); });
        favTab.setOnClickListener(v -> { favoritesOnly=true; filterChannels(); });
        drawer.addView(tabs);

        FrameLayout listArea = new FrameLayout(this);
        adapter = new ChannelAdapter();
        channelList = new ListView(this);
        channelList.setAdapter(adapter);
        channelList.setDivider(null);
        channelList.setDividerHeight(0);
        channelList.setCacheColorHint(Color.TRANSPARENT);
        channelList.setBackgroundColor(Color.TRANSPARENT);
        channelList.setVerticalScrollBarEnabled(false);
        channelList.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        listArea.addView(channelList, new FrameLayout.LayoutParams(-1, -1));

        emptyText = text("Каналы не найдены", 15, DIM, false);
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setPadding(dp(14), dp(22), dp(14), dp(22));
        listArea.addView(emptyText, new FrameLayout.LayoutParams(-1, -1));
        channelList.setEmptyView(emptyText);
        drawer.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));

        countLabel = text("Загрузка списка…", 12, 0xff94a4b9, false);
        countLabel.setPadding(dp(20), dp(13), dp(15), dp(20));
        drawer.addView(countLabel);
        filterChannels();
    }

    private TextView tab(String label) {
        TextView view = text(label, 15, DIM, true);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setClickable(true);
        view.setBackground(rounded(0xff1a2636, 10));
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }

    private void showControls() {
        if(drawerOpen) return;
        handler.removeCallbacks(hideTask);
        controls.animate().cancel();
        controls.setVisibility(View.VISIBLE);
        controls.setAlpha(1f);
        controlsVisible = true;
        scheduleHide();
    }

    private void hideControls() {
        if(drawerOpen) return;
        handler.removeCallbacks(hideTask);
        if(!controlsVisible) return;
        controlsVisible = false;
        controls.animate().alpha(0f).setDuration(230).withEndAction(() -> {
            if(!controlsVisible && !drawerOpen) controls.setVisibility(View.GONE);
        }).start();
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideTask);
        if(!drawerOpen && controlsVisible) handler.postDelayed(hideTask, AUTO_HIDE_MS);
    }

    private void openDrawer() {
        handler.removeCallbacks(hideTask);
        controls.animate().cancel();
        controls.setVisibility(View.GONE);
        controlsVisible = false;
        drawerOpen = true;
        shade.setVisibility(View.VISIBLE);
        drawer.setVisibility(View.VISIBLE);
        drawer.setTranslationX(-drawer.getLayoutParams().width);
        drawer.animate().translationX(0f).setDuration(200).start();
        filterChannels();
        if(current >= 0 && query.isEmpty() && !favoritesOnly) {
            int position = shown.indexOf(current);
            if(position >= 0) channelList.setSelection(position);
        }
        channelList.requestFocus();
    }

    private void closeDrawer() {
        if(!drawerOpen) return;
        drawerOpen = false;
        ((InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE))
            .hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        drawer.animate().cancel();
        drawer.setTranslationX(0f);
        drawer.setVisibility(View.GONE);
        shade.setVisibility(View.GONE);
        showControls();
    }

    private void setMessage(String value) {
        if(message == null) return;
        message.setVisibility(value == null ? View.GONE : View.VISIBLE);
        if(value != null) message.setText(value);
    }

    private int positionOf(Channel channel) {
        for(int i=0;i<channels.size();i++) if(channels.get(i) == channel) return i;
        return -1;
    }

    private void filterChannels() {
        if(adapter == null) return;
        shown.clear();
        for(int i=0; i<channels.size(); i++) {
            Channel c = channels.get(i);
            if(favoritesOnly && !favorites.contains(c.key())) continue;
            if(!query.isEmpty() && !c.name.toLowerCase(Locale.ROOT).contains(query)) continue;
            shown.add(i);
        }
        allTab.setTextColor(favoritesOnly ? DIM : BLUE);
        favTab.setTextColor(favoritesOnly ? BLUE : DIM);
        allTab.setBackground(rounded(favoritesOnly ? 0xff1a2636 : 0xff294967, 10));
        favTab.setBackground(rounded(favoritesOnly ? 0xff294967 : 0xff1a2636, 10));
        adapter.notifyDataSetChanged();
        if(countLabel != null) countLabel.setText("Найдено: "+shown.size()+"  ·  Всего: "+channels.size());
    }

    private void toggleFavorite(Channel channel) {
        if(!favorites.add(channel.key())) favorites.remove(channel.key());
        prefs.edit().putStringSet("favorites", new HashSet<>(favorites)).apply();
        updateCurrent();
        filterChannels();
    }

    private void updateCurrent() {
        if(current < 0 || current >= channels.size()) {
            channelTitle.setText("РФ ТВ");
            channelSubtitle.setText("● ПРЯМОЙ ЭФИР  ·  IPTV");
            favoriteButton.setText("☆");
            return;
        }
        Channel c = channels.get(current);
        channelTitle.setText(c.name);
        channelSubtitle.setText("● ПРЯМОЙ ЭФИР  ·  IPTV");
        favoriteButton.setText(favorites.contains(c.key()) ? "★" : "☆");
        favoriteButton.setTextColor(favorites.contains(c.key()) ? YELLOW : WHITE);
    }

    private void play(int index) {
        if(index < 0 || index >= channels.size()) return;
        current = index;
        Channel c = channels.get(index);
        prefs.edit().putString("last", c.url).apply();
        player.setMediaItem(MediaItem.fromUri(c.url));
        player.prepare();
        player.play();
        updateCurrent();
        setMessage("Подключаемся к эфиру…");
        adapter.notifyDataSetChanged();
        if(drawerOpen) closeDrawer();
        else showControls();
    }

    private void move(int delta) {
        if(channels.isEmpty()) { openDrawer(); return; }
        int next = current < 0 ? 0 : (current + delta + channels.size()) % channels.size();
        play(next);
    }

    private int channelRank(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        if(n.equals("первый канал") || n.equals("1tv") || n.equals("channel one")) return 0;
        if(n.equals("россия 1") || n.equals("russia 1")) return 1;
        if(n.equals("нтв") || n.equals("ntv")) return 2;
        if(n.equals("тнт") || n.equals("tnt")) return 3;
        if(n.equals("стс") || n.equals("sts")) return 4;
        if(n.equals("пятый канал") || n.equals("5 канал")) return 5;
        return 100;
    }

    private void loadChannels() {
        if(loading) return;
        loading = true;
        if(channels.isEmpty()) setMessage("Загружаем российские телеканалы…");
        new Thread(() -> {
            String content = null;
            boolean cached = false;
            try {
                HttpURLConnection connection = (HttpURLConnection)new URL(SOURCE).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(16000);
                connection.setRequestProperty("User-Agent", "RF-TV/2.0");
                try {
                    if(connection.getResponseCode() != 200) throw new Exception("HTTP "+connection.getResponseCode());
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    try(InputStream in = connection.getInputStream()) {
                        byte[] b = new byte[8192]; int n;
                        while((n = in.read(b)) != -1) {
                            output.write(b, 0, n);
                            if(output.size() > 2000000) throw new Exception("Playlist too large");
                        }
                    }
                    content = output.toString("UTF-8");
                    if(PlaylistParser.parse(content).isEmpty()) throw new Exception("No channels");
                    try(FileOutputStream out = openFileOutput("channels.m3u", MODE_PRIVATE)) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                    }
                } finally { connection.disconnect(); }
            } catch(Exception exception) {
                cached = true;
                try(FileInputStream in = openFileInput("channels.m3u");
                    ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int length;
                    while((length = in.read(buffer)) != -1) data.write(buffer, 0, length);
                    content = data.toString("UTF-8");
                } catch(Exception ignored) {}
            }
            List<Channel> result = PlaylistParser.parse(content);
            result.sort(Comparator.comparingInt((Channel c) -> channelRank(c.name))
                .thenComparing(c -> c.name.toLowerCase(Locale.ROOT)));
            boolean useCache = cached;
            runOnUiThread(() -> {
                if(isFinishing() || isDestroyed()) return;
                loading = false;
                String oldUrl = current >= 0 && current < channels.size()
                    ? channels.get(current).url : prefs.getString("last", "");
                channels.clear();
                channels.addAll(result);
                int found = -1;
                for(int i=0;i<channels.size();i++) {
                    if(channels.get(i).url.equals(oldUrl)) { found=i; break; }
                }
                boolean playing = current >= 0 && player.getMediaItemCount() > 0;
                current = found;
                filterChannels();
                updateCurrent();
                if(channels.isEmpty()) {
                    setMessage("Не удалось загрузить каналы. Проверьте подключение.");
                    showControls();
                    return;
                }
                if(!playing) play(found >= 0 ? found : 0);
                else {
                    if(current < 0) current = 0;
                    setMessage(null);
                    updateCurrent();
                    if(useCache) showControls();
                }
            });
        }).start();
    }

    private class ChannelAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int p) { return channels.get(shown.get(p)); }
        @Override public long getItemId(int p) { return shown.get(p); }

        @Override public View getView(int p, View convertView, ViewGroup parent) {
            Channel c = channels.get(shown.get(p));
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(4), dp(5), dp(4));
            row.setMinimumHeight(dp(67));
            row.setBackground(rounded(shown.get(p) == current ? 0xff324b63 : 0x00192634, 11));
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(v -> play(positionOf(c)));
            row.setOnFocusChangeListener((v, focused) -> row.setBackground(
                rounded(focused ? 0xff3d607f : (positionOf(c) == current ? 0xff324b63 : 0x00192634), 11)
            ));

            TextView number = text(Integer.toString(shown.get(p)+1), 13, 0xff93a4b9, false);
            number.setGravity(Gravity.CENTER);
            row.addView(number, new LinearLayout.LayoutParams(dp(30), dp(50)));

            FrameLayout icon = new FrameLayout(MainActivity.this);
            TextView placeholder = text(c.name.isEmpty() ? "TV" : c.name.substring(0, 1).toUpperCase(Locale.ROOT), 22, WHITE, true);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setBackground(rounded(0xff34475a, 8));
            icon.addView(placeholder, new FrameLayout.LayoutParams(dp(43), dp(43), Gravity.CENTER));
            ImageView logo = new ImageView(MainActivity.this);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.addView(logo, new FrameLayout.LayoutParams(dp(43), dp(43), Gravity.CENTER));
            row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(49)));
            fetchLogo(c.logo, logo, placeholder);

            TextView name = text(c.name, 15, WHITE, false);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, -2, 1f);
            nameParams.leftMargin = dp(12);
            row.addView(name, nameParams);

            TextView star = text(favorites.contains(c.key()) ? "★" : "☆", 25,
                favorites.contains(c.key()) ? YELLOW : DIM, false);
            star.setContentDescription("Добавить или удалить из избранного");
            star.setGravity(Gravity.CENTER);
            star.setFocusable(true);
            star.setClickable(true);
            star.setOnClickListener(v -> toggleFavorite(c));
            row.addView(star, new LinearLayout.LayoutParams(dp(44), dp(52)));
            LinearLayout wrap = new LinearLayout(MainActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(dp(11), dp(2), dp(11), dp(2));
            wrap.addView(row, new LinearLayout.LayoutParams(-1, -2));
            return wrap;
        }
    }

    private void fetchLogo(String url, ImageView view, TextView placeholder) {
        view.setImageDrawable(null);
        if(url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        view.setTag(url);
        Bitmap hit = iconCache.get(url);
        if(hit != null) {
            view.setImageBitmap(hit);
            placeholder.setVisibility(View.INVISIBLE);
            return;
        }
        iconThreads.execute(() -> {
            Bitmap image = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection)new URL(url).openConnection();
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(4500);
                connection.setRequestProperty("User-Agent", "RF-TV/2.0");
                if(connection.getResponseCode() != 200) return;
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try(InputStream in = connection.getInputStream()) {
                    byte[] bytes = new byte[4096]; int n;
                    while((n = in.read(bytes)) != -1) {
                        output.write(bytes,0,n);
                        if(output.size() > 250000) return;
                    }
                }
                byte[] data = output.toByteArray();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data,0,data.length,opts);
                int sample = 1;
                while(opts.outWidth / sample > 120 || opts.outHeight / sample > 120) sample *= 2;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = sample;
                image = BitmapFactory.decodeByteArray(data,0,data.length,opts);
                if(image != null) iconCache.put(url,image);
            } catch(Exception ignored) {}
            finally { if(connection != null) connection.disconnect(); }
            Bitmap result = image;
            if(result != null) runOnUiThread(() -> {
                if(url.equals(view.getTag())) {
                    view.setImageBitmap(result);
                    placeholder.setVisibility(View.INVISIBLE);
                }
            });
        });
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(drawerOpen) return super.onKeyDown(keyCode,event);
        if(keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
           (!controlsVisible && keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            move(1); return true;
        }
        if(keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
           (!controlsVisible && keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            move(-1); return true;
        }
        if(keyCode == KeyEvent.KEYCODE_MENU) { openDrawer(); return true; }
        if(keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if(!controlsVisible) { showControls(); return true; }
        }
        return super.onKeyDown(keyCode,event);
    }

    @Override public void onBackPressed() {
        if(drawerOpen) { closeDrawer(); return; }
        if(controlsVisible) { hideControls(); return; }
        super.onBackPressed();
    }

    @Override protected void onStop() {
        super.onStop();
        if(player != null) player.pause();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        iconThreads.shutdownNow();
        if(player != null) player.release();
        super.onDestroy();
    }
}
