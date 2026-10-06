package com.glowingmushroom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** An ordered list of waypoints that the macro walks in a loop. */
public class Route {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<List<double[]>>() {}.getType();

    private final List<double[]> points = new ArrayList<>();
    private final Path file;

    public Route(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                List<double[]> loaded = GSON.fromJson(r, TYPE);
                if (loaded != null) points.addAll(loaded);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
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
        points.removeLast();
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

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(points, TYPE, w);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
