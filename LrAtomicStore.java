package android.server.power.nextapp;

import android.util.AtomicFile;
import android.util.Slog;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public final class LrAtomicStore {
    private static final String TAG = "NextAppLrStore";
    private static final int MAGIC = 0x4E41504C; // "NAPL"
    private static final int VERSION = 1;

    private final AtomicFile mFile;

    public LrAtomicStore(File file) {
        mFile = new AtomicFile(file);
    }

    public void write(OnlineLogisticModel lr, int hashDimPow2) {
        if (lr == null) return;

        FileOutputStream fos = null;
        try {
            fos = mFile.startWrite();
            DataOutputStream out = new DataOutputStream(fos);

            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(hashDimPow2);
            out.writeFloat(lr.getBias());

            float[] w = lr.getWeights();
            out.writeInt(w.length);
            for (float v : w) out.writeFloat(v);

            out.flush();
            fos.getFD().sync();
            mFile.finishWrite(fos);
        } catch (Throwable t) {
            Slog.e(TAG, "LR save failed: " + mFile.getBaseFile(), t);
            if (fos != null) mFile.failWrite(fos);
        }
    }

    /** Returns null if missing/corrupt/mismatched. */
    public LoadedLr readOrNull() {
        try (FileInputStream fis = mFile.openRead();
             DataInputStream in = new DataInputStream(fis)) {

            if (in.readInt() != MAGIC) return null;
            int ver = in.readInt();
            if (ver != VERSION) return null;

            int pow2 = in.readInt();
            float bias = in.readFloat();

            int len = in.readInt();
            float[] w = new float[len];
            for (int i = 0; i < len; i++) w[i] = in.readFloat();

            return new LoadedLr(pow2, bias, w);

        } catch (Throwable t) {
            return null;
        }
    }

    public static final class LoadedLr {
        public final int hashDimPow2;
        public final float bias;
        public final float[] weights;
        LoadedLr(int hashDimPow2, float bias, float[] weights) {
            this.hashDimPow2 = hashDimPow2;
            this.bias = bias;
            this.weights = weights;
        }
    }
}
