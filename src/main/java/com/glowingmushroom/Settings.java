package com.glowingmushroom;

import com.glowingmushroom.pathing.PathMemory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/** Small persistent toggles (auto update, path learning). */
public final class Settings {
    private Settings() {}

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path file;

    public static void load(Path f) {
        file = f;
        if (!Files.exists(f)) return;
        try (Reader r = Files.newBufferedReader(f)) {
            JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
            if (o.has("autoUpdate")) Updater.auto = o.get("autoUpdate").getAsBoolean();
            if (o.has("learning")) PathMemory.enabled = o.get("learning").getAsBoolean();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void save() {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("autoUpdate", Updater.auto);
            o.addProperty("learning", PathMemory.enabled);
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(o, w);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
