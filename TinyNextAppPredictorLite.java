package com.android.server.maxpower.chain;

import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Slog;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public final class TinyNextAppPredictorLite {
    private static final String TAG = "TinyPredictorLite";

    private static final int D = 2048;
    private static final float DEFAULT_LR = 0.05f;
    private static final float L2 = 1e-5f;

    private final float[] mW = new float[D];
    private long mUpdates = 0;

    private final AtomicFile mFile;

    private long mLastSaveUptime;
    private static final long MIN_SAVE_INTERVAL_MS = 10 * 60 * 1000;
    private static final long MIN_UPDATES_BETWEEN_SAVES = 50;

    public TinyNextAppPredictorLite(AtomicFile file) {
        mFile = file;
    }

    public float inferProbability(String pkgA, String pkgB) {
        int[] idx = features(pkgA, pkgB);
        float z = 0f;
        for (int i = 0; i < idx.length; i++) z += mW[idx[i]];
        return sigmoid(z);
    }

    public void train(String pkgA, String pkgB, int label) {
        int[] idx = features(pkgA, pkgB);
        float z = 0f;
        for (int i = 0; i < idx.length; i++) z += mW[idx[i]];
        float p = sigmoid(z);
        float y = label != 0 ? 1f : 0f;
        float err = (y - p);

        float lr = (float) (DEFAULT_LR / (1.0 + 0.0005 * mUpdates));

        for (int i = 0; i < idx.length; i++) {
            int k = idx[i];
            float w = mW[k];
            w = w * (1f - lr * L2) + lr * err;
            mW[k] = w;
        }
        mUpdates++;
    }

    public void load() {
        try {
            FileInputStream fis = mFile.openRead();
            DataInputStream in = new DataInputStream(new BufferedInputStream(fis));
            int d = in.readInt();
            if (d != D) {
                Slog.w(TAG, "D mismatch. expected=" + D + " got=" + d + " reset.");
                in.close();
                return;
            }
            long upd = in.readLong();
            for (int i = 0; i < D; i++) mW[i] = in.readFloat();
            mUpdates = upd;
            in.close();
            Slog.i(TAG, "Loaded model updates=" + mUpdates);
        } catch (FileNotFoundException e) {
            Slog.i(TAG, "No model file yet");
        } catch (IOException e) {
            Slog.w(TAG, "Load failed", e);
        }
    }

    public void maybeSave() {
        long now = SystemClock.uptimeMillis();
        if ((mUpdates % MIN_UPDATES_BETWEEN_SAVES) != 0) return;
        if (now - mLastSaveUptime < MIN_SAVE_INTERVAL_MS) return;
        saveNow();
        mLastSaveUptime = now;
    }

    public void saveNow() {
        FileOutputStream fos = null;
        try {
            fos = mFile.startWrite();
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos));
            out.writeInt(D);
            out.writeLong(mUpdates);
            for (int i = 0; i < D; i++) out.writeFloat(mW[i]);
            out.flush();
            mFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.w(TAG, "Save failed", e);
            if (fos != null) mFile.failWrite(fos);
        }
    }

    // -------- features: only A->B (+bias, optional A,B) --------

    private int[] features(String pkgA, String pkgB) {
        ArrayList<Integer> list = new ArrayList<>(4);
        list.add(hash("BIAS"));
        list.add(hash("A2B=" + pkgA + "->" + pkgB));
        // optional but useful:
        list.add(hash("A=" + pkgA));
        list.add(hash("B=" + pkgB));

        int[] idx = new int[list.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = list.get(i);
        return idx;
    }

    private int hash(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        int h = 0x811c9dc5;
        for (int i = 0; i < b.length; i++) {
            h ^= (b[i] & 0xff);
            h *= 0x01000193;
        }
        int idx = h % D;
        if (idx < 0) idx += D;
        return idx;
    }

    private static float sigmoid(float z) {
        if (z > 20f) return 1f;
        if (z < -20f) return 0f;
        return (float) (1.0 / (1.0 + Math.exp(-z)));
    }
}
