package com.glowingmushroom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.util.Vec3;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/** An ordered list of waypoints that the macro walks in a loop. */
public class Route {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<List<double[]>>() {}.getType();

    private final List<double[]> points = new ArrayList<double[]>();
    private final File file;

    public Route(File file) {
        this.file = file;
        load();
    }

    public void add(double x, double y, double z) {
        points.add(new double[]{x, y, z});
        save();
    }

    public void clear() {
        points.clear();
        save();
    }

    public boolean removeLast() {
        if (points.isEmpty()) return false;
        points.remove(points.size() - 1);
        save();
        return true;
    }

    public int size() {
        return points.size();
    }

    public Vec3 get(int i) {
        double[] p = points.get(i % points.size());
        return new Vec3(p[0], p[1], p[2]);
    }

    private void load() {
        if (!file.exists()) return;
        try (FileReader r = new FileReader(file)) {
            List<double[]> loaded = GSON.fromJson(r, TYPE);
            if (loaded != null) points.addAll(loaded);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void save() {
        try (FileWriter w = new FileWriter(file)) {
            GSON.toJson(points, TYPE, w);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
