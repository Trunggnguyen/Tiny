package com.android.server.maxpower.chain;

public final class ChainGuard {
    private static final long COOLDOWN_MS = 15_000;
    private static final long ANTI_LOOP_MS = 30_000;

    private final Object mLock = new Object();
    private String mLastFrom;
    private String mLastTo;
    private long mLastAt;

    public boolean allowChain(String pkgA, String pkgB, long nowUptime) {
        if (pkgA == null || pkgB == null) return false;
        if (pkgA.equals(pkgB)) return false;

        synchronized (mLock) {
            if (nowUptime - mLastAt < COOLDOWN_MS) return false;
            if (nowUptime - mLastAt < ANTI_LOOP_MS) {
                if (pkgA.equals(mLastTo) && pkgB.equals(mLastFrom)) return false;
            }
        }
        return true;
    }

    public void onChained(String pkgA, String pkgB, long nowUptime) {
        synchronized (mLock) {
            mLastFrom = pkgA;
            mLastTo = pkgB;
            mLastAt = nowUptime;
        }
    }
}
