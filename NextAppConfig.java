package android.server.power.nextapp;

/**
 * Configuration knobs for "next-app prefetch unsuspend".
 *
 * Goal:
 * - When app A is allowed-to-run (unsuspended), predict the next likely app(s) B
 *   and pre-unsuspend them to reduce launch latency, while avoiding unnecessary wakeups.
 *
 * This config is designed for:
 * - Non-system apps with launcher icons that may be suspended in MaxPower mode.
 */
public final class NextAppConfig {

    // =========================
    // Markov (candidate generation)
    // =========================

    /**
     * For each source app A, keep only the top-M next apps B by transition weight.
     * This bounds memory and keeps the model stable.
     *
     * Typical: 30-50
     */
    public int markovTopMPerA = 50;

    /**
     * When A is allowed-to-run, fetch only the top-N Markov candidates to score/rank.
     * This bounds CPU cost during prediction.
     *
     * Typical: 10-20 (Markov-only can be smaller: 5-10)
     */
    public int candidateTopN = 20;

    /**
     * Exponential decay factor applied to Markov transition weights.
     * On update: weight = weight * decay + 1
     *
     * Closer to 1.0 => "forgets" slower.
     * Typical: 0.9990 - 0.9997
     */
    public float markovDecay = 0.9995f;

    // =========================
    // Policy (power-safety gate)
    // =========================

    /**
     * Maximum number of apps B to pre-unsuspend per A.
     * Keep this small for battery safety.
     *
     * Recommended: start with 1
     */
    public int prefetchTopK = 1;

    /**
     * Minimum confidence score required to pre-unsuspend the top candidate.
     * If score < threshold => do not prefetch anything.
     *
     * Typical: 0.65 - 0.75 (be more conservative in MaxPower / low battery)
     */
    public float threshold = 0.70f;

    /**
     * Confidence gap between the top1 and top2 candidates.
     * Ensures the decision is not ambiguous (top1 must be clearly better than top2).
     *
     * Typical: 0.08 - 0.15
     */
    public float gapDelta = 0.10f;

    /**
     * Time-to-live for a prefetched unsuspend (milliseconds).
     * If the user does not open B within TTL, the system should roll back (re-suspend)
     * and record a "very hard negative" for learning.
     *
     * Typical: 20s - 60s
     */
    public int ttlMs = 30_000;

    // =========================
    // Logistic Regression + Hashing (optional reranker)
    // =========================

    /**
     * Enable Logistic Regression reranking of Markov candidates.
     * If disabled, prediction relies on Markov ranking alone.
     */
    public boolean enableLr = true;

    /**
     * Hashing trick vector size is 2^hashDimPow2.
     * 2^16 = 65536 weights (~256KB as float[]), usually a good tradeoff.
     *
     * Typical: 15 or 16
     */
    public int hashDimPow2 = 16;

    /**
     * SGD learning rate for online Logistic Regression updates.
     * Higher => learns faster but can be noisier.
     *
     * Typical: 0.03 - 0.10
     */
    public float lr = 0.05f;

    /**
     * L2 regularization strength for LR to prevent weights from exploding.
     *
     * Typical: 1e-6 to 1e-5
     */
    public float l2 = 1e-6f;

    /**
     * Number of "hard negative" samples to train per positive transition.
     * Hard negatives come from candidates that were plausible but not actually opened.
     *
     * Typical: 3 - 5 (too high can make the model overly conservative)
     */
    public int hardNegPerPos = 5;

    // =========================
    // Persistence / checkpointing
    // =========================

    /**
     * Save/checkpoint the model to disk after this many online updates.
     * This avoids writing on every event (reduces I/O).
     *
     * Typical: 200 - 500
     */
    public int checkpointEveryNUpdates = 300;

    // =========================
    // Master switch
    // =========================

    /** Kill switch for the entire feature. */
    public boolean enable = true;

    @Override
    public String toString() {
        return "NextAppConfig{"
                + "markovTopMPerA=" + markovTopMPerA
                + ", candidateTopN=" + candidateTopN
                + ", markovDecay=" + markovDecay
                + ", prefetchTopK=" + prefetchTopK
                + ", threshold=" + threshold
                + ", gapDelta=" + gapDelta
                + ", ttlMs=" + ttlMs
                + ", enableLr=" + enableLr
                + ", hashDimPow2=" + hashDimPow2
                + ", lr=" + lr
                + ", l2=" + l2
                + ", hardNegPerPos=" + hardNegPerPos
                + ", checkpointEveryNUpdates=" + checkpointEveryNUpdates
                + ", enable=" + enable
                + '}';
    }
}
