package com.pubburi.pub.recommendation;

import java.util.List;

/** Sorted private choices already scoped to the current approved catalog snapshot. */
public record FeedbackSignals(List<Integer> matched, List<Integer> notInterested) {
    public static final FeedbackSignals EMPTY = new FeedbackSignals(List.of(), List.of());
    private static final int PROVIDER_LIMIT = 20;

    public FeedbackSignals {
        matched = List.copyOf(matched);
        notInterested = List.copyOf(notInterested);
    }

    public boolean isEmpty() {
        return matched.isEmpty() && notInterested.isEmpty();
    }

    public boolean withinProviderLimit() {
        return matched.size() <= PROVIDER_LIMIT && notInterested.size() <= PROVIDER_LIMIT;
    }

    /** Implemented only by the runtime that owns feedback persistence; joins the caller's snapshot. */
    public interface Reader {
        FeedbackSignals readFeedback(String userId, List<Integer> productIds);
    }
}
