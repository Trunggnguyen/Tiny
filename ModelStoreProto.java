package com.android.server.maxpower.chain;

import android.util.AtomicFile;
import android.util.Slog;

import com.android.server.maxpower.chain.proto.NextAppModelProto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

public final class ModelStoreProto {
    private static final String TAG = "ModelStoreProto";
    private static final int VERSION = 1;

    private final AtomicFile mFile;

    public ModelStoreProto(AtomicFile file) {
        mFile = file;
    }

    public void loadInto(TinyNextAppModel model) {
        try (FileInputStream fis = mFile.openRead()) {
            NextAppModelProto.NextAppModel proto =
                    NextAppModelProto.NextAppModel.parseFrom(new BufferedInputStream(fis));

            if (!proto.hasDimension() || proto.getDimension() != TinyNextAppModel.D) {
                Slog.w(TAG, "dimension mismatch/reset");
                return;
            }

            int n = proto.getWeightsCount();
            float[] w = model.weights();
            int copy = Math.min(w.length, n);
            for (int i = 0; i < copy; i++) w[i] = proto.getWeights(i);

            if (proto.hasUpdateCount()) model.setUpdates(proto.getUpdateCount());
        } catch (FileNotFoundException e) {
            // first run
        } catch (IOException e) {
            Slog.w(TAG, "load failed", e);
        }
    }

    public void saveFrom(TinyNextAppModel model) {
        FileOutputStream fos = null;
        try {
            fos = mFile.startWrite();
            NextAppModelProto.NextAppModel.Builder b =
                    NextAppModelProto.NextAppModel.newBuilder()
                            .setVersion(VERSION)
                            .setDimension(TinyNextAppModel.D)
                            .setUpdateCount(model.getUpdates());

            float[] w = model.weights();
            for (float v : w) b.addWeights(v);

            b.build().writeTo(new BufferedOutputStream(fos));
            mFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.w(TAG, "save failed", e);
            if (fos != null) mFile.failWrite(fos);
        }
    }
}
