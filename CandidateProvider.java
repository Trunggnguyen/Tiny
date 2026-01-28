package com.android.server.maxpower.chain;

import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Candidates = apps with launcher icon AND non-system.
 * Caches per userId. Refresh on demand (you can also hook package broadcasts).
 */
public final class CandidateProvider {
    private static final String TAG = "CandidateProvider";

    private final Context mContext;
    private final PackageManager mPm;
    private final Random mRand = new Random();

    private final Object mLock = new Object();
    private final ArrayMap<Integer, List<String>> mCacheByUser = new ArrayMap<>();
    private final ArrayMap<Integer, ArraySet<String>> mCacheSetByUser = new ArrayMap<>();

    public CandidateProvider(Context context) {
        mContext = context;
        mPm = context.getPackageManager();
    }

    public List<String> getCandidates(int userId) {
        synchronized (mLock) {
            List<String> cached = mCacheByUser.get(userId);
            if (cached != null) return cached;
        }

        List<String> fresh = queryLauncherNonSystem(userId);

        synchronized (mLock) {
            mCacheByUser.put(userId, fresh);
            ArraySet<String> set = new ArraySet<>(fresh.size());
            set.addAll(fresh);
            mCacheSetByUser.put(userId, set);
        }
        return fresh;
    }

    public void invalidate(int userId) {
        synchronized (mLock) {
            mCacheByUser.remove(userId);
            mCacheSetByUser.remove(userId);
        }
    }

    public boolean isEligibleApp(String pkg, int userId) {
        if (pkg == null) return false;
        ArraySet<String> set;
        synchronized (mLock) { set = mCacheSetByUser.get(userId); }
        if (set == null) {
            // build cache lazily
            getCandidates(userId);
            synchronized (mLock) { set = mCacheSetByUser.get(userId); }
        }
        return set != null && set.contains(pkg);
    }

    public List<String> sampleNegatives(int userId, String pkgA, String realB, int count) {
        List<String> cands = getCandidates(userId);
        if (cands.isEmpty()) return Collections.emptyList();

        ArrayList<String> out = new ArrayList<>(count);
        int attempts = 0;

        while (out.size() < count && attempts < 50) {
            attempts++;
            String b = cands.get(mRand.nextInt(cands.size()));
            if (b.equals(pkgA) || b.equals(realB)) continue;
            if (out.contains(b)) continue;
            out.add(b);
        }
        return out;
    }

    private List<String> queryLauncherNonSystem(int userId) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> ris = mPm.queryIntentActivitiesAsUser(
                i, PackageManager.MATCH_DEFAULT_ONLY, userId);

        ArraySet<String> pkgs = new ArraySet<>();
        for (int idx = 0; idx < ris.size(); idx++) {
            ResolveInfo ri = ris.get(idx);
            if (ri == null || ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null) continue;

            if (isSystemApp(ri.activityInfo.applicationInfo)) continue;
            pkgs.add(pkg);
        }

        ArrayList<String> out = new ArrayList<>(pkgs.size());
        out.addAll(pkgs);
        Slog.i(TAG, "Candidates user=" + userId + " size=" + out.size());
        return out;
    }

    private static boolean isSystemApp(ApplicationInfo ai) {
        if (ai == null) return true;
        final boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        // updated system apps still might be "system-ish"; decide your policy:
        final boolean updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        return system || updatedSystem;
    }
}
