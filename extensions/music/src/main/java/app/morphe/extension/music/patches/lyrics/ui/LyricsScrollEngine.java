/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.ui.Dim;

/**
 * High-performance line-change scroll and spring cascade transition engine.
 *
 * <p>Key architectural properties:
 * <ul>
 *   <li>Instant {@link ScrollView#scrollTo(int, int)} with applied-delta calculation.</li>
 *   <li>Single {@link ValueAnimator} driving all row translations with zero per-frame allocations.</li>
 *   <li>Under-damped spring easing via {@link SpringEasing}.</li>
 *   <li>In-flight 400ms gating lock with coalescing pending updates.</li>
 *   <li>Far-jump handling via {@link ScrollView#smoothScrollTo(int, int)} when distance > 2x viewport height.</li>
 *   <li>Directional staggered wave propagation for smooth row transitions.</li>
 * </ul>
 */
public final class LyricsScrollEngine {

    public interface Callback {
        boolean isUserScrolling();
        void setProgrammaticScrolling(boolean isProgrammatic);
        void onRecomputeAndScroll(int referenceIndex);
    }

    private static final long IN_FLIGHT_LOCK_MS = 400L;
    private static final float NATIVE_SCROLL_DISTANCE_RATIO = 2.0f;
    private static final float SKIP_THRESHOLD_PX = 0.5f;

    private static final class RowSpec {
        final View view;
        final float startDelta;
        final long delayMs;
        final int durationMs;
        final SpringEasing.Result easing;

        RowSpec(View view, float startDelta, long delayMs, int durationMs, SpringEasing.Result easing) {
            this.view = view;
            this.startDelta = startDelta;
            this.delayMs = delayMs;
            this.durationMs = durationMs;
            this.easing = easing;
        }
    }

    private final ScrollView scrollView;
    private final LinearLayout linesContainer;
    private final View footerContainer;
    private final Callback callback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isAnimating = false;
    private boolean hasPendingUpdate = false;
    private int pendingTargetIndex = -1;

    @Nullable
    private ValueAnimator currentAnimator = null;
    @Nullable
    private RowSpec[] currentSpecs = null;
    private int currentSpecCount = 0;

    private final Runnable unlockRunnable = new Runnable() {
        @Override
        public void run() {
            isAnimating = false;
            if (hasPendingUpdate) {
                hasPendingUpdate = false;
                final int targetIdx = pendingTargetIndex;
                pendingTargetIndex = -1;
                if (!callback.isUserScrolling()) {
                    callback.onRecomputeAndScroll(targetIdx);
                }
            }
        }
    };

    public LyricsScrollEngine(@NonNull ScrollView scrollView,
                              @NonNull LinearLayout linesContainer,
                              @NonNull View footerContainer,
                              @NonNull Callback callback) {
        this.scrollView = scrollView;
        this.linesContainer = linesContainer;
        this.footerContainer = footerContainer;
        this.callback = callback;
    }

    public boolean isAnimating() {
        return isAnimating;
    }

    /**
     * Resets any running cascades and pending updates.
     */
    public void reset() {
        cancelCascade();
        handler.removeCallbacks(unlockRunnable);
        isAnimating = false;
        hasPendingUpdate = false;
        pendingTargetIndex = -1;
    }

    /**
     * Overload for backwards compatibility.
     */
    public void scrollTo(int targetY,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         boolean forceInstant) {
        scrollTo(targetY, referenceView, referenceIndex, referenceView, referenceIndex, lookAheadMs, forceInstant);
    }

    /**
     * Performs line-change autoscroll with spring bounce cascade.
     *
     * @param targetY Destination scroll Y position
     * @param targetView The primary line row view acting as target for the scroll
     * @param targetIndex The primary line index in lyrics
     * @param referenceView The previously active line row view acting as anchor for the cascade
     * @param referenceIndex The previously active line index in lyrics
     * @param lookAheadMs Lookahead timing in ms for spring peak duration tuning
     * @param forceInstant If true, snaps immediately without spring cascade (e.g. user seek)
     */
    public void scrollTo(int targetY,
                         @Nullable View targetView,
                         int targetIndex,
                         @Nullable View referenceView,
                         int referenceIndex,
                         long lookAheadMs,
                         boolean forceInstant) {
        if (scrollView.getHeight() <= 0 || linesContainer.getChildCount() <= 0) {
            return;
        }
        if (callback.isUserScrolling()) {
            return;
        }

        // In-flight gating: queue latest target if animation is locked and not forced
        if (isAnimating && !forceInstant) {
            pendingTargetIndex = targetIndex;
            hasPendingUpdate = true;
            return;
        }

        final int target = Math.max(0, targetY);
        final int currentScrollY = scrollView.getScrollY();
        final float requestedDelta = (float) (target - currentScrollY);

        // Early return if already at target
        if (Math.abs(requestedDelta) < 1.0f && !forceInstant) {
            return;
        }

        // Forced jump (seek / track change): snap instantly without animation
        if (forceInstant) {
            reset();
            callback.setProgrammaticScrolling(true);
            scrollView.scrollTo(0, target);
            callback.setProgrammaticScrolling(false);
            return;
        }

        final int viewportHeight = scrollView.getHeight();

        // Far-jump threshold: |delta| > viewportHeight * 2 uses smooth scroll
        if (Math.abs(requestedDelta) > viewportHeight * NATIVE_SCROLL_DISTANCE_RATIO) {
            reset();
            scrollView.smoothScrollTo(0, target);
            return;
        }

        // Cancel existing cascade: rows snap to rest before starting new cascade
        cancelCascade();

        // Lock in-flight animation for BASE_DURATION (400ms)
        isAnimating = true;
        handler.removeCallbacks(unlockRunnable);
        handler.postDelayed(unlockRunnable, IN_FLIGHT_LOCK_MS);

        // Snap scroll position to compute applied delta (boundary clamping)
        final int beforeY = scrollView.getScrollY();
        callback.setProgrammaticScrolling(true);
        scrollView.scrollTo(0, target);
        final int afterY = scrollView.getScrollY();
        callback.setProgrammaticScrolling(false);

        final float appliedDelta = (float) (afterY - beforeY);
        if (Math.abs(appliedDelta) < SKIP_THRESHOLD_PX) {
            return;
        }

        // Row discovery across linesContainer in document order
        final List<View> validRows = new ArrayList<>(linesContainer.getChildCount());
        final int childCount = linesContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = linesContainer.getChildAt(i);
            if (child == footerContainer) {
                continue;
            }
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            // Instrumental gaps with 0 height are not active on screen
            if (child instanceof GapLineView && child.getHeight() <= 0) {
                continue;
            }
            validRows.add(child);
        }

        final int totalRows = validRows.size();
        if (totalRows == 0) {
            return;
        }

        // Find target row index in validRows
        int targetRowIdx = -1;
        if (targetView != null) {
            for (int i = 0; i < totalRows; i++) {
                final View row = validRows.get(i);
                if (row == targetView || (row instanceof ViewGroup && ((ViewGroup) row).indexOfChild(targetView) >= 0)) {
                    targetRowIdx = i;
                    break;
                }
            }
        }
        if (targetRowIdx < 0) {
            targetRowIdx = Math.max(0, Math.min(targetIndex, totalRows - 1));
        }

        // Find reference row index in validRows
        int refRowIdx = -1;
        if (referenceView != null) {
            for (int i = 0; i < totalRows; i++) {
                final View row = validRows.get(i);
                if (row == referenceView || (row instanceof ViewGroup && ((ViewGroup) row).indexOfChild(referenceView) >= 0)) {
                    refRowIdx = i;
                    break;
                }
            }
        }
        if (refRowIdx < 0) {
            refRowIdx = (referenceIndex >= 0) ? Math.max(0, Math.min(referenceIndex, totalRows - 1)) : targetRowIdx;
        }

        final int refIdx = refRowIdx;

        // Visible range calculation
        final int minViewY = Math.min(beforeY, target);
        final int maxViewY = Math.max(beforeY, target) + viewportHeight;

        int visMin = refIdx;
        int visMax = refIdx;
        for (int i = 0; i < totalRows; i++) {
            final View row = validRows.get(i);
            if (row.getBottom() >= minViewY && row.getTop() <= maxViewY) {
                if (i < visMin) visMin = i;
                if (i > visMax) visMax = i;
            }
        }

        int start = Math.max(0, Math.min(Math.min(visMin, refIdx), targetRowIdx));
        final int reachBottom = target + viewportHeight + (int) Math.abs(appliedDelta) + Dim.dp(200);
        int end = Math.min(totalRows, Math.max(Math.max(visMax, refIdx), targetRowIdx) + 1);
        while (end < totalRows && validRows.get(end).getTop() < reachBottom) {
            end++;
        }
        end = Math.min(totalRows, end + 3);

        final int count = end - start;
        if (count <= 0) {
            return;
        }

        // Spring and timing constants
        final double peakTimeMs = Math.min(450.0, Math.max(350.0, (double) lookAheadMs));
        final SpringEasing.Result easing = SpringEasing.get(peakTimeMs, 0.78, peakTimeMs * 1.5);
        final int durationMs = easing.durationMs;
        final float delayIncrement = (float) (peakTimeMs * 0.1);

        // Directional cascade stagger
        final boolean scrollingDown;
        if (targetRowIdx > refRowIdx) {
            scrollingDown = true;
        } else if (targetRowIdx < refRowIdx) {
            scrollingDown = false;
        } else {
            scrollingDown = (appliedDelta >= 0);
        }

        final RowSpec[] specs = new RowSpec[count];
        int specIdx = 0;

        if (scrollingDown) {
            int n = 0;
            for (int i = start; i < end; i++) {
                final View row = validRows.get(i);
                final boolean isGap = (row instanceof GapLineView);
                final long delay = (i >= refIdx) ? Math.round(n * delayIncrement) : 0L;
                if (i >= refIdx && !isGap) {
                    n++;
                }
                specs[specIdx++] = new RowSpec(row, appliedDelta, delay, durationMs, easing);
            }
        } else {
            int n = 0;
            for (int i = end - 1; i >= start; i--) {
                final View row = validRows.get(i);
                final boolean isGap = (row instanceof GapLineView);
                final long delay = (i <= refIdx) ? Math.round(n * delayIncrement) : 0L;
                if (i <= refIdx && !isGap) {
                    n++;
                }
                specs[specIdx++] = new RowSpec(row, appliedDelta, delay, durationMs, easing);
            }
        }

        // Single driver ValueAnimator: pre-set initial translation and start animation
        long maxTotalMs = 0L;
        for (int i = 0; i < specIdx; i++) {
            final RowSpec spec = specs[i];
            maxTotalMs = Math.max(maxTotalMs, spec.delayMs + spec.durationMs);
            spec.view.setTranslationY(spec.startDelta); // fill: backwards
        }

        currentSpecs = specs;
        currentSpecCount = specIdx;

        final long totalDurationMs = maxTotalMs;
        final ValueAnimator animator = ValueAnimator.ofInt(0, (int) totalDurationMs);
        currentAnimator = animator;
        animator.setDuration(totalDurationMs);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(animation -> {
            final long playTime = (long) (animation.getAnimatedFraction() * totalDurationMs);
            final RowSpec[] arr = currentSpecs;
            final int len = currentSpecCount;
            if (arr == null) {
                return;
            }
            for (int i = 0; i < len; i++) {
                final RowSpec spec = arr[i];
                final long t = playTime - spec.delayMs;
                if (t <= 0) {
                    spec.view.setTranslationY(spec.startDelta);
                } else if (t >= spec.durationMs) {
                    spec.view.setTranslationY(0f);
                } else {
                    final float p = (float) t / (float) spec.durationMs;
                    final float eased = spec.easing.interpolate(p);
                    spec.view.setTranslationY(spec.startDelta * (1f - eased));
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finishCascade();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finishCascade();
            }
        });
        animator.start();
    }

    private void finishCascade() {
        if (currentSpecs != null) {
            for (int i = 0; i < currentSpecCount; i++) {
                currentSpecs[i].view.setTranslationY(0f);
            }
            currentSpecs = null;
            currentSpecCount = 0;
        }
        currentAnimator = null;
    }

    public void cancelCascade() {
        if (currentAnimator != null) {
            currentAnimator.cancel();
            currentAnimator = null;
        }
        finishCascade();
    }
}
