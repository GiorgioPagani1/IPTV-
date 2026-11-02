package ru.rftv.app;

import android.content.SharedPreferences;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** User-defined ordering, pinned and hidden channels persist across app launches. */
public final class ChannelSettings {
    private final SharedPreferences prefs;
    private final ArrayList<String> order = new ArrayList<>();
    private final Set<String> pinned = new HashSet<>();
    private final Set<String> hidden = new HashSet<>();

    public ChannelSettings(SharedPreferences prefs) {
        this.prefs = prefs;
        pinned.addAll(prefs.getStringSet("pinned_v22", Collections.emptySet()));
        hidden.addAll(prefs.getStringSet("hidden_v22", Collections.emptySet()));
        try {
            JSONArray arr = new JSONArray(prefs.getString("order_v22", "[]"));
            for(int i=0;i<arr.length();i++) {
                String id=arr.optString(i,"");
                if(!id.isEmpty() && !order.contains(id)) order.add(id);
            }
        } catch(Exception ignored) { }
    }

    public boolean pinned(Channel c) { return pinned.contains(c.key()); }
    public boolean hidden(Channel c) { return hidden.contains(c.key()); }
    public int hiddenCount(List<Channel> channels) {
        int result=0;
        for(Channel c:channels) if(hidden(c)) result++;
        return result;
    }
    public void togglePin(Channel c) {
        if(!pinned.add(c.key())) pinned.remove(c.key());
        prefs.edit().putStringSet("pinned_v22",new HashSet<>(pinned)).apply();
    }
    public void toggleHidden(Channel c) {
        if(!hidden.add(c.key())) hidden.remove(c.key());
        prefs.edit().putStringSet("hidden_v22",new HashSet<>(hidden)).apply();
    }

    public void sort(List<Channel> channels) {
        for(Channel c:channels) if(!order.contains(c.key())) order.add(c.key());
        channels.sort((a,b)->{
            if(pinned(a) != pinned(b)) return pinned(a) ? -1 : 1;
            return Integer.compare(order.indexOf(a.key()), order.indexOf(b.key()));
        });
        saveOrder();
    }

    public boolean move(Channel channel, int direction, List<Channel> sortedChannels) {
        int pos=-1;
        for(int i=0;i<sortedChannels.size();i++)
            if(sortedChannels.get(i).key().equals(channel.key())) {pos=i;break;}
        int target=pos+direction;
        if(pos<0 || target<0 || target>=sortedChannels.size()) return false;
        Channel other=sortedChannels.get(target);
        if(pinned(channel) != pinned(other)) return false;
        int a=order.indexOf(channel.key()), b=order.indexOf(other.key());
        if(a<0 || b<0) return false;
        Collections.swap(order,a,b);
        saveOrder();
        sort(sortedChannels);
        return true;
    }

    private void saveOrder() {
        JSONArray values=new JSONArray();
        for(String key:order) values.put(key);
        prefs.edit().putString("order_v22",values.toString()).apply();
    }
}
