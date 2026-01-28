package com.android.server.maxpower.chain;

import android.os.SystemClock;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.server.maxpower.chain.proto.ChainBufferProto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Collect transitions A->B (input already filtered) and persist to Proto via AtomicFile.
 */
public final class TransitionBufferStore {
    private static final String TAG = "TransitionBufferStore";

    private final int mMaxPairs;                 // e.g. 10_000
    private final int mMinEventsBetweenSaves;    // e.g. 200
    private final long mMinSaveIntervalMs;       // e.g. 60_000

    private final Object mLock = new Object();
    private final AtomicFile mFile;

    // key="A\0B" -> count
    private final ArrayMap<String, Integer> mCounts = new ArrayMap<>();

    private boolean mDirty = false;
    private int mEventsSinceLastSave = 0;
    private long mLastSaveUptimeMs = 0;

    public TransitionBufferStore(AtomicFile file, int maxPairs, int minEventsBetweenSaves, long minSaveIntervalMs) {
        mFile = file;
        mMaxPairs = maxPairs;
        mMinEventsBetweenSaves = minEventsBetweenSaves;
        mMinSaveIntervalMs = minSaveIntervalMs;
    }

    /** Event input */
    public void onTransition(String pkgA, String pkgB) {
        if (pkgA == null || pkgB == null) return;
        if (pkgA.equals(pkgB)) return;

        synchronized (mLock) {
            String key = makeKey(pkgA, pkgB);
            Integer cur = mCounts.get(key);
            if (cur == null) cur = 0;
            mCounts.put(key, cur + 1);

            mDirty = true;
            mEventsSinceLastSave++;

            if (mCounts.size() > mMaxPairs) trimToCapLocked();
        }
    }

    /** Restore at boot */
    public void load() {
        synchronized (mLock) {
            mCounts.clear();
            mDirty = false;
            mEventsSinceLastSave = 0;
        }
        try (FileInputStream fis = mFile.openRead()) {
            ChainBufferProto.TransitionBuffer proto =
                    ChainBufferProto.TransitionBuffer.parseFrom(new BufferedInputStream(fis));

            synchronized (mLock) {
                for (int i = 0; i < proto.getPairsCount(); i++) {
                    ChainBufferProto.PairCount pc = proto.getPairs(i);
                    String a = pc.getA();
                    String b = pc.getB();
                    int c = pc.getCount();
                    if (a == null || b == null || c <= 0) continue;
                    mCounts.put(makeKey(a, b), c);
                }
                trimToCapLocked();
                mLastSaveUptimeMs = SystemClock.uptimeMillis();
            }
            Slog.i(TAG, "Loaded pairs=" + proto.getPairsCount());
        } catch (FileNotFoundException e) {
            // first run
        } catch (IOException e) {
            Slog.w(TAG, "load failed", e);
        }
    }

    /** Save immediately (screen off / shutdown) */
    public void saveNow() {
        final ChainBufferProto.TransitionBuffer proto;
        synchronized (mLock) {
            proto = buildProtoLocked();
        }
        writeProtoAtomic(proto);

        synchronized (mLock) {
            mDirty = false;
            mEventsSinceLastSave = 0;
            mLastSaveUptimeMs = SystemClock.uptimeMillis();
        }
    }

    /** Throttled save (call periodically or on screen off) */
    public void maybeSave() {
        final long now = SystemClock.uptimeMillis();
        final boolean should;
        synchronized (mLock) {
            if (!mDirty) return;
            boolean byEvents = mEventsSinceLastSave >= mMinEventsBetweenSaves;
            boolean byTime = (now - mLastSaveUptimeMs) >= mMinSaveIntervalMs;
            should = byEvents || byTime;
        }
        if (should) saveNow();
    }

    /** Drain for trainer (and clear memory) */
    public ArrayMap<String, Integer> drainAndClear() {
        synchronized (mLock) {
            ArrayMap<String, Integer> out = new ArrayMap<>(mCounts);
            mCounts.clear();
            mDirty = true; // because content changed, persist empty after train
            mEventsSinceLastSave = mMinEventsBetweenSaves; // force next maybeSave
            return out;
        }
    }

    /** Persist empty after a successful train so old data won't be trained again */
    public void persistEmpty() {
        synchronized (mLock) {
            mCounts.clear();
            mDirty = true;
            mEventsSinceLastSave = mMinEventsBetweenSaves;
        }
        saveNow();
    }

    /** Snapshot for predictor candidates if needed elsewhere */
    public ArrayMap<String, Integer> snapshotCounts() {
        synchronized (mLock) {
            return new ArrayMap<>(mCounts);
        }
    }

    // -------- internals --------

    private ChainBufferProto.TransitionBuffer buildProtoLocked() {
        ChainBufferProto.TransitionBuffer.Builder b =
                ChainBufferProto.TransitionBuffer.newBuilder()
                        .setVersion(1)
                        .setLastWriteUptimeMs(SystemClock.uptimeMillis());

        for (int i = 0; i < mCounts.size(); i++) {
            String key = mCounts.keyAt(i);
            int count = mCounts.valueAt(i);
            if (count <= 0) continue;
            String[] ab = splitKey(key);
            b.addPairs(ChainBufferProto.PairCount.newBuilder()
                    .setA(ab[0])
                    .setB(ab[1])
                    .setCount(count)
                    .build());
        }
        return b.build();
    }

    private void writeProtoAtomic(ChainBufferProto.TransitionBuffer proto) {
        FileOutputStream fos = null;
        try {
            fos = mFile.startWrite();
            proto.writeTo(new BufferedOutputStream(fos));
            mFile.finishWrite(fos);
        } catch (IOException e) {
            Slog.w(TAG, "save failed", e);
            if (fos != null) mFile.failWrite(fos);
        }
    }

    private static String makeKey(String a, String b) { return a + "\0" + b; }

    static String[] splitKey(String key) {
        int idx = key.indexOf('\0');
        if (idx < 0) return new String[]{key, ""};
        return new String[]{key.substring(0, idx), key.substring(idx + 1)};
    }

    private void trimToCapLocked() {
        while (mCounts.size() > mMaxPairs) {
            int minIdx = -1, minVal = Integer.MAX_VALUE;
            for (int i = 0; i < mCounts.size(); i++) {
                int v = mCounts.valueAt(i);
                if (v < minVal) { minVal = v; minIdx = i; }
            }
            if (minIdx >= 0) mCounts.removeAt(minIdx);
            else break;
        }
    }
}
