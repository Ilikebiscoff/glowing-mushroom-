package com.glowingmushroom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Names of players you don't want to share a lobby with (friends, other users of the mod, ...).
 * Checked against the tab list when joining/swapping lobbies and before the macro starts.
 */
public class AvoidList {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<List<String>>() {}.getType();

    private final TreeSet<String> names = new TreeSet<>(); // lowercase
    private final Path file;

    public AvoidList(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                List<String> loaded = GSON.fromJson(r, TYPE);
                if (loaded != null) for (String n : loaded) names.add(n.toLowerCase(Locale.ROOT));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public synchronized boolean add(String name) {
        boolean added = names.add(name.trim().toLowerCase(Locale.ROOT));
        if (added) save();
        return added;
    }

    public synchronized boolean remove(String name) {
        boolean removed = names.remove(name.trim().toLowerCase(Locale.ROOT));
        if (removed) save();
        return removed;
    }

    public synchronized void clear() {
        names.clear();
        save();
    }

    public synchronized List<String> list() {
        return new ArrayList<>(names);
    }

    public synchronized boolean isEmpty() {
        return names.isEmpty();
    }

    /** The first listed player found in the tab list (not counting yourself), or null. */
    public synchronized String scan(Minecraft mc) {
        if (names.isEmpty() || mc.getConnection() == null) return null;
        String me = mc.player == null ? "" : mc.player.getName().getString().toLowerCase(Locale.ROOT);
        for (var info : mc.getConnection().getOnlinePlayers()) {
            String n = info.getProfile().name().toLowerCase(Locale.ROOT);
            if (!n.equals(me) && names.contains(n)) return info.getProfile().name();
        }
        return null;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(new ArrayList<>(names), TYPE, w);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
