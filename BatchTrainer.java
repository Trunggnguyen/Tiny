package com.android.server.maxpower.chain;

import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Trains model from (A,B)->count pairs and negative sampling.
 * Input already filtered, candidates list already filtered.
 */
public final class BatchTrainer {
    private final TinyNextAppModel mModel;
    private final CandidateProvider mCandidates;
    private final Random mRand = new Random();

    // tune
    private final int mNegSamples; // e.g. 3

    public BatchTrainer(TinyNextAppModel model, CandidateProvider candidates, int negSamples) {
        mModel = model;
        mCandidates = candidates;
        mNegSamples = negSamples;
    }

    public void trainFromCounts(ArrayMap<String, Integer> counts, int userId) {
        if (counts == null || counts.isEmpty()) return;

        List<String> cand = mCandidates.getCandidates(userId);
        if (cand.isEmpty()) return;

        for (int i = 0; i < counts.size(); i++) {
            String key = counts.keyAt(i);
            int c = counts.valueAt(i);
            if (c <= 0) continue;

            String[] ab = TransitionBufferStore.splitKey(key);
            String a = ab[0];
            String b = ab[1];

            // positive weighted
            mModel.train(a, b, 1, c);

            // negative sampling (light)
            for (int s = 0; s < mNegSamples; s++) {
                String neg = sampleNeg(cand, a, b);
                if (neg != null) mModel.train(a, neg, 0, 1);
            }
        }
    }

    private String sampleNeg(List<String> cand, String a, String realB) {
        for (int tries = 0; tries < 20; tries++) {
            String b = cand.get(mRand.nextInt(cand.size()));
            if (b.equals(a) || b.equals(realB)) continue;
            return b;
        }
        return null;
    }
}
