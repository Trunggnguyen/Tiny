package com.android.server.maxpower.chain;

import android.os.SystemClock;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class AppChainManager {
    private static final String TAG = "AppChainManager";

    // Train window: chỉ train khi B mở trong khoảng này sau A launched
    public static final long WINDOW_MS = 15_000;

    // Trigger threshold: xác suất tối thiểu để auto-open B
    private static final float THRESHOLD = 0.80f;

    // số app trả về để mở (hoặc bạn chỉ mở 1 cái top-1)
    private static final int DEFAULT_TOPK = 1;

    private final Object mLock = new Object();

    private final CandidateProvider mCandidateProvider;
    private final TinyNextAppPredictorLite mPredictor;
    private final ChainGuard mGuard;
    private final AppLauncher mLauncher;
    private final TopAppChecker mTopChecker;

    // pending A launch for "clean training"
    private PendingLaunch mPending;

    static final class PendingLaunch {
        final String pkgA;
        final int userId;
        final long t0Uptime;
        PendingLaunch(String pkgA, int userId, long t0Uptime) {
            this.pkgA = pkgA;
            this.userId = userId;
            this.t0Uptime = t0Uptime;
        }
    }

    public AppChainManager(
            CandidateProvider candidateProvider,
            TinyNextAppPredictorLite predictor,
            ChainGuard guard,
            AppLauncher launcher,
            TopAppChecker topChecker) {
        mCandidateProvider = candidateProvider;
        mPredictor = predictor;
        mGuard = guard;
        mLauncher = launcher;
        mTopChecker = topChecker;
    }

    /**
     * Call when launcher icon path opens A (or your "A unsuspend event" moment).
     * Purpose: predict and auto-open B.
     */
    public void onLauncherLaunched(String pkgA, int userId) {
        if (pkgA == null) return;
        final long now = SystemClock.uptimeMillis();

        // 1) set pending for clean training later
        synchronized (mLock) {
            mPending = new PendingLaunch(pkgA, userId, now);
        }

        // 2) predict best B and open it
        List<String> candidates = mCandidateProvider.getCandidates(userId);
        if (candidates.isEmpty()) return;

        List<Prediction> best = predictTopK(pkgA, candidates, DEFAULT_TOPK);
        for (int i = 0; i < best.size(); i++) {
            String pkgB = best.get(i).pkg;
            float score = best.get(i).score;

            if (score < THRESHOLD) continue;
            if (!mGuard.allowChain(pkgA, pkgB, now)) continue;

            // Check B is not already top
            String top = mTopChecker.getTopPackage(userId);
            if (pkgB.equals(top)) {
                Slog.d(TAG, "Skip launch; B already top. B=" + pkgB);
                continue;
            }

            // Launch B (startActivity) (you can change to "unsuspend only" if needed)
            mLauncher.launch(pkgB, userId);

            mGuard.onChained(pkgA, pkgB, now);
            break; // only launch top-1 by default
        }
    }

    /**
     * Call when foreground app transitions A -> B.
     * Purpose: clean training if within WINDOW_MS of last launcher launch A.
     */
    public void onTransition(String fromPkgA, String toPkgB, int userId) {
        if (fromPkgA == null || toPkgB == null) return;
        final long now = SystemClock.uptimeMillis();

        // Only train if matches pending launcher launch and within window
        PendingLaunch p;
        synchronized (mLock) { p = mPending; }

        if (p == null) return;
        if (p.userId != userId) return;
        if (!fromPkgA.equals(p.pkgA)) return;
        if (now - p.t0Uptime > WINDOW_MS) return;
        if (fromPkgA.equals(toPkgB)) return;

        // Filter to launcher/non-system pairs for clean data
        if (!mCandidateProvider.isEligibleApp(fromPkgA, userId)) return;
        if (!mCandidateProvider.isEligibleApp(toPkgB, userId)) return;

        // Train: positive + negative sampling
        synchronized (mLock) {
            mPredictor.train(fromPkgA, toPkgB, 1);

            List<String> negs = mCandidateProvider.sampleNegatives(userId, fromPkgA, toPkgB, 3);
            for (int i = 0; i < negs.size(); i++) {
                mPredictor.train(fromPkgA, negs.get(i), 0);
            }
            mPredictor.maybeSave();
        }
    }

    // ---------------- predictions ----------------

    static final class Prediction {
        final String pkg;
        final float score;
        Prediction(String pkg, float score) {
            this.pkg = pkg;
            this.score = score;
        }
    }

    private List<Prediction> predictTopK(String pkgA, List<String> candidates, int k) {
        ArrayList<Prediction> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            String b = candidates.get(i);
            if (pkgA.equals(b)) continue;
            float p = mPredictor.inferProbability(pkgA, b);
            scored.add(new Prediction(b, p));
        }
        Collections.sort(scored, (o1, o2) -> Float.compare(o2.score, o1.score));

        if (scored.size() <= k) return scored;
        return scored.subList(0, k);
    }
}
