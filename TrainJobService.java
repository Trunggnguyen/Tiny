package com.android.server.maxpower.chain.job;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class TrainJobService extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        // Call into your framework/service singleton to run training.
        // Return false since we finish immediately.
        boolean didTrain = TrainBridge.tryTrainNow(getApplicationContext());
        jobFinished(params, !didTrain); // reschedule if not trained
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // retry if interrupted
    }
}
