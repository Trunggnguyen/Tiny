package com.android.server.maxpower.chain;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.Slog;

public final class AppLauncher {
    private static final String TAG = "AppLauncher";

    private final Context mContext;
    private final PackageManager mPm;

    public AppLauncher(Context context) {
        mContext = context;
        mPm = context.getPackageManager();
    }

    public void launch(String pkg, int userId) {
        Intent launch = mPm.getLaunchIntentForPackage(pkg);
        if (launch == null) {
            Slog.w(TAG, "No launch intent for " + pkg);
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            mContext.startActivityAsUser(launch, UserHandle.of(userId));
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to launch " + pkg, t);
        }
    }
}
