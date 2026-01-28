package com.android.server.maxpower.chain;

import java.nio.charset.StandardCharsets;

public final class TinyNextAppModel {
    public static final int D = 2048;

    private final float[] w = new float[D];
    private long updates = 0;

    // tune
    private static final float LR0 = 0.05f;
    private static final float L2 = 1e-5f;

    public float infer(String a, String b) {
        int[] idx = feats(a, b);
        float z = 0f;
        for (int k : idx) z += w[k];
        return sigmoid(z);
    }

    /** weighted SGD: weight>=1 speeds up batch training */
    public void train(String a, String b, int label, int weight) {
        int[] idx = feats(a, b);
        float z = 0f;
        for (int k : idx) z += w[k];
        float p = sigmoid(z);
        float y = (label != 0) ? 1f : 0f;
        float err = (y - p);

        float lr = (float)(LR0 / (1.0 + 0.0005 * updates));
        float step = lr * Math.max(1, weight);

        for (int k : idx) {
            float wk = w[k];
            wk = wk * (1f - step * L2) + step * err;
            w[k] = wk;
        }
        updates++;
    }

    public float[] weights() { return w; }
    public long getUpdates() { return updates; }
    public void setUpdates(long u) { updates = u; }

    private static int[] feats(String a, String b) {
        // bias + A2B + optional A,B
        return new int[] {
                hash("BIAS"),
                hash("A2B=" + a + "->" + b),
                hash("A=" + a),
                hash("B=" + b)
        };
    }

    private static int hash(String s) {
        byte[] bb = s.getBytes(StandardCharsets.UTF_8);
        int h = 0x811c9dc5;
        for (byte value : bb) {
            h ^= (value & 0xff);
            h *= 0x01000193;
        }
        int idx = h % D;
        if (idx < 0) idx += D;
        return idx;
    }

    private static float sigmoid(float z) {
        if (z > 20f) return 1f;
        if (z < -20f) return 0f;
        return (float)(1.0 / (1.0 + Math.exp(-z)));
    }
}
