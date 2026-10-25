package dev.flexy.dynamicisland.xposed;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.lang.reflect.Method;
import java.util.ArrayDeque;

import dev.flexy.dynamicisland.island.IslandController;
import dev.flexy.dynamicisland.island.IslandItem;
import dev.flexy.dynamicisland.island.IslandView;

final class ShadeLiveCards {
    private static final String ROW = "com.android.systemui.statusbar.notification.row.";
    private static final String BINDER = ROW + "NotificationRowContentBinderImpl";
    private static final String[] LIMITS = {"mSmallHeight", "mMinContractedHeight", "mHeadsUpHeight"};

    private ShadeLiveCards() {
    }

    static void install(ClassLoader cl) {
        try {
            int apply = Refl.hookAll(cl, BINDER + "$Companion", "access$apply", new SafeHook("shade card reapply") {
                @Override
                protected void before(MethodHookParam p) {
                    State s = p.args.length > 8 ? state(Refl.get(p.args[8], "mPrivateLayout")) : null;
                    if (s != null) s.exposeOriginals();
                }

                @Override
                protected void after(MethodHookParam p) {
                    State s = p.args.length > 8 ? exposed(Refl.get(p.args[8], "mPrivateLayout")) : null;
                    if (s != null) s.hideOriginals();
                }
            });
            if (apply == 0) return;
            int measure = Refl.hookAll(cl, ROW + "NotificationContentView", "onMeasure",
                    new SafeHook("shade card measure") {
                        @Override
                        protected void before(MethodHookParam p) {
                            State s = state(p.thisObject);
                            if (s != null) s.beforeMeasure(View.MeasureSpec.getSize((Integer) p.args[0]));
                        }

                        @Override
                        protected void after(MethodHookParam p) {
                            State s = state(p.thisObject);
                            if (s != null) s.afterMeasure();
                        }
                    });
            int minHeight = Refl.hookAll(cl, ROW + "NotificationContentView", "getMinHeight",
                    SafeHook.after("shade card min height", p -> {
                        State s = state(p.thisObject);
                        if (s != null && !p.hasThrowable()) {
                            p.setResult(s.card.getHeight() > 0 ? s.card.getHeight() : s.card.desiredHeight);
                        }
                    }));
            if (measure == 0 || minHeight == 0) return;
            Refl.hookAll(cl, ROW + "wrapper.NotificationViewWrapper", "needsInversion",
                    SafeHook.before("shade card colors", p -> {
                        if (Refl.get(p.thisObject, "mView") instanceof LiveCardView) p.setResult(false);
                    }));
            Refl.hookAll(cl, BINDER + "$AsyncInflationTask", "onAsyncInflationFinished",
                    SafeHook.after("shade live card bind", p -> {
                        if (!p.hasThrowable()) bind(Refl.get(p.thisObject, "row"));
                    }));
        } catch (Throwable t) {
            XLog.e("shade live card hooks failed", t);
        }
    }

    private static IslandItem item(String key) {
        IslandController c = IslandController.get();
        return c == null ? null : c.itemForKey(key);
    }

    static boolean isCardRow(android.view.View row) {
        Object layout = Refl.get(row, "mPrivateLayout");
        return layout != null && (state(layout) != null || EXPOSED.containsKey(layout));
    }

    private static State state(Object layout) {
        Object child = Refl.get(layout, "mContractedChild");
        return child instanceof LiveCardView ? ((LiveCardView) child).state : null;
    }

    private static final java.util.Map<Object, State> EXPOSED = new java.util.WeakHashMap<>();

    private static State exposed(Object layout) {
        return layout == null ? null : EXPOSED.get(layout);
    }

    private static void bind(Object row) {
        if (!(row instanceof View)) return;
        String key = (String) Refl.call(Refl.get(row, "mEntryAdapter"), "getKey");
        IslandItem item = item(key);
        if (item == null) return;
        Object layout = Refl.get(row, "mPrivateLayout");
        if (!(layout instanceof ViewGroup)) return;
        State previous = state(layout);
        if (previous != null) previous.restore();
        State s = null;
        try {
            s = new State((View) row, (ViewGroup) layout, key, item);
            s.apply();
        } catch (Throwable t) {
            XLog.e("shade live card bind failed", t);
            if (s != null) s.restore();
        }
    }

    private static final class State {
        final View row;
        final ViewGroup layout;
        final View contracted;
        final View expanded;
        final View headsUp;
        final Method setContracted;
        final Method setExpanded;
        final Method setHeadsUp;
        final Method updateExpandButtons;
        final Method selectLayout;
        final boolean expandable;
        final boolean rowExpandable;
        final LiveCardView card;
        final LiveCardView headsUpCard;
        final ArrayDeque<int[]> measureLimits = new ArrayDeque<>();
        boolean restored;
        boolean restorePosted;

        State(View row, ViewGroup layout, String key, IslandItem item) throws Throwable {
            this.row = row;
            this.layout = layout;
            contracted = (View) Refl.get(layout, "mContractedChild");
            expanded = (View) Refl.get(layout, "mExpandedChild");
            headsUp = (View) Refl.get(layout, "mHeadsUpChild");
            setContracted = required(layout, "setContractedChild", 1);
            setExpanded = required(layout, "setExpandedChild", 1);
            setHeadsUp = required(layout, "setHeadsUpChild", 1);
            updateExpandButtons = required(layout, "updateExpandButtons", 1);
            selectLayout = required(layout, "selectLayout", 2);
            for (String name : LIMITS) {
                if (Refl.field(layout.getClass(), name) == null) throw new NoSuchFieldException(name);
            }
            expandable = Refl.getBool(layout, "mExpandable", false);
            if (Refl.field(row.getClass(), "mExpandable") == null) throw new NoSuchFieldException("mExpandable");
            rowExpandable = Refl.getBool(row, "mExpandable", false);
            card = new LiveCardView(row.getContext(), this, key, item);
            headsUpCard = headsUp == null ? null : new LiveCardView(row.getContext(), this, key, item);
            int width = layout.getWidth() - layout.getPaddingLeft() - layout.getPaddingRight();
            card.heightFor(Math.max(1, width > 0 ? width : row.getResources().getDisplayMetrics().widthPixels));
        }

        void apply() throws Throwable {
            setContracted.invoke(layout, card);
            setExpanded.invoke(layout, new Object[]{null});
            if (headsUpCard != null) setHeadsUp.invoke(layout, headsUpCard);
            updateExpandButtons.invoke(layout, false);
            if (!Refl.set(row, "mExpandable", false)) throw new IllegalStateException("row mExpandable");
            selectLayout.invoke(layout, false, true);
            layout.requestLayout();
            Refl.call(row, "notifyHeightChanged", "island live card", false);
            ShadeCardTheme.refresh(row);
        }

        void exposeOriginals() {
            if (restored) return;
            EXPOSED.put(layout, this);
            Refl.set(layout, "mContractedChild", contracted);
            Refl.set(layout, "mExpandedChild", expanded);
            if (headsUpCard != null) Refl.set(layout, "mHeadsUpChild", headsUp);
        }

        void hideOriginals() {
            EXPOSED.remove(layout);
            if (restored) return;
            if (Refl.get(layout, "mContractedChild") == contracted) Refl.set(layout, "mContractedChild", card);
            if (Refl.get(layout, "mExpandedChild") == expanded) Refl.set(layout, "mExpandedChild", null);
            if (headsUpCard != null && Refl.get(layout, "mHeadsUpChild") == headsUp) Refl.set(layout, "mHeadsUpChild", headsUpCard);
        }

        void beforeMeasure(int width) {
            int[] old = new int[LIMITS.length];
            try {
                int height = card.heightFor(Math.max(1, width - layout.getPaddingLeft() - layout.getPaddingRight()));
                for (int i = 0; i < LIMITS.length; i++) old[i] = Refl.getInt(layout, LIMITS[i], 0);
                measureLimits.push(old);
                for (String name : LIMITS) {
                    if (!Refl.set(layout, name, height)) throw new IllegalStateException(name);
                }
            } catch (Throwable t) {
                afterMeasure();
                fail(t);
            }
        }

        void afterMeasure() {
            if (measureLimits.isEmpty()) return;
            int[] old = measureLimits.pop();
            for (int i = 0; i < LIMITS.length; i++) Refl.set(layout, LIMITS[i], old[i]);
        }

        void fail(Throwable t) {
            if (restored || restorePosted) return;
            XLog.e("shade live card failed", t);
            restorePosted = true;
            row.post(this::restore);
        }

        void restore() {
            if (restored) return;
            restored = true;
            while (!measureLimits.isEmpty()) afterMeasure();
            restoreChild(setContracted, "mContractedChild", card, contracted);
            restoreChild(setExpanded, "mExpandedChild", null, expanded);
            if (headsUpCard != null) restoreChild(setHeadsUp, "mHeadsUpChild", headsUpCard, headsUp);
            if (!Refl.set(row, "mExpandable", rowExpandable)) XLog.i("shade live card restore mExpandable failed");
            try {
                updateExpandButtons.invoke(layout, expandable);
                selectLayout.invoke(layout, false, true);
                layout.requestLayout();
                Refl.call(row, "notifyHeightChanged", "island live card restore", false);
                ShadeCardTheme.refresh(row);
            } catch (Throwable t) {
                XLog.e("shade live card restore failed", t);
            }
        }

        private void restoreChild(Method setter, String field, View replacement, View original) {
            try {
                Object current = Refl.get(layout, field);
                if (current == replacement || (current == original && original != null && original.getParent() != layout)) {
                    setter.invoke(layout, new Object[]{original});
                }
            } catch (Throwable t) {
                XLog.e("shade live card restore " + field, t);
            }
        }
    }

    private static Method required(Object obj, String name, int count) throws NoSuchMethodException {
        Method m = Refl.method(obj.getClass(), name, count);
        if (m == null) throw new NoSuchMethodException(name);
        return m;
    }

    private static final class LiveCardView extends FrameLayout {
        final State state;
        final String key;
        final IslandView renderer;
        final float slop;
        IslandItem current;
        IslandItem downItem;
        float downX;
        float downY;
        boolean tracking;
        boolean torchTracking;
        boolean moved;
        int desiredHeight;
        final Runnable tick = this::onTick;

        private void onTick() {
            if (isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE && !state.restored) {
                invalidate();
            }
        }

        View remote;
        android.widget.RemoteViews remoteSource;
        final int remotePad;

        LiveCardView(Context ctx, State state, String key, IslandItem item) {
            super(ctx);
            setWillNotDraw(false);
            remotePad = Math.round(14f * ctx.getResources().getDisplayMetrics().density);
            this.state = state;
            this.key = key;
            current = item;
            renderer = new IslandView(ctx, null);
            slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
            setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            setOnClickListener(v -> {
                try {
                    IslandItem latest = item(key);
                    if (!state.restored && !moved && latest != null && latest == downItem
                            && renderer.shadeCardTap(latest, getWidth(), downX, downY)) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                    }
                } catch (Throwable t) {
                    state.fail(t);
                }
            });
            setOnLongClickListener(v -> {
                moved = true;
                try {
                    int[] here = new int[2];
                    int[] there = new int[2];
                    getLocationOnScreen(here);
                    state.row.getLocationOnScreen(there);
                    required(state.row, "doLongClickCallback", 2).invoke(state.row,
                            Math.round(downX + here[0] - there[0]), Math.round(downY + here[1] - there[1]));
                    return true;
                } catch (Throwable t) {
                    XLog.e("shade live card long click failed", t);
                    return false;
                }
            });
        }

        private final Runnable goneCheck = this::checkGone;

        private void checkGone() {
            if (!state.restored && isAttachedToWindow() && item(key) == null) {
                state.fail(new IllegalStateException("live activity gone: " + key));
            }
        }

        int heightFor(int width) {
            IslandItem latest = item(key);
            if (latest == null) {
                if (current == null) state.fail(new IllegalStateException("live activity gone: " + key));
                removeCallbacks(goneCheck);
                postDelayed(goneCheck, 700);
            } else {
                current = latest;
            }
            if (current.remoteView != null) {
                View v = remote();
                int cw = Math.max(1, width - remotePad * 2);
                v.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.round(300f * getResources().getDisplayMetrics().density), MeasureSpec.AT_MOST));
                desiredHeight = Math.max(remotePad * 2 + 1, v.getMeasuredHeight() + remotePad * 2);
            } else {
                if (remote != null) {
                    removeView(remote);
                    remote = null;
                    remoteSource = null;
                }
                desiredHeight = Math.max(1, (int) Math.ceil(renderer.shadeCardHeight(current, width)));
            }
            return desiredHeight;
        }

        private View remote() {
            android.widget.RemoteViews rv = current.remoteView;
            if (remote != null && remoteSource == rv) return remote;
            if (remote != null && dev.flexy.dynamicisland.island.IslandView.canReapply(remoteSource, rv)) {
                try {
                    rv.reapply(IslandView.nightContext(getContext()), remote);
                    remoteSource = rv;
                    return remote;
                } catch (Throwable ignored) {
                }
            }
            if (remote != null) removeView(remote);
            remote = rv.apply(IslandView.nightContext(getContext()), this);
            remoteSource = rv;
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.setMargins(remotePad, remotePad, remotePad, remotePad);
            addView(remote, lp);
            return remote;
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec);
            try {
                setMeasuredDimension(width, resolveSize(heightFor(Math.max(1, width)), heightSpec));
                if (remote != null) {
                    remote.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width - remotePad * 2), MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(Math.max(1, getMeasuredHeight() - remotePad * 2), MeasureSpec.EXACTLY));
                }
            } catch (Throwable t) {
                setMeasuredDimension(width, MeasureSpec.getSize(heightSpec));
                state.fail(t);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            try {
                IslandItem latest = item(key);
                if (latest != null && latest.remoteView != null) {
                    if (latest != current) {
                        current = latest;
                        post(this::requestLayout);
                    }
                } else {
                    int height = heightFor(Math.max(1, getWidth()));
                    if (height != getMeasuredHeight()) requestLayout();
                    renderer.drawShadeCard(canvas, current, getWidth());
                    if (renderer.shadeButtonAnimating()) postInvalidateOnAnimation();
                }
                ShadeCardTheme.ensureBlur(state.row);
                CharSequence title = current.title != null ? current.title : current.appName;
                setContentDescription((title == null ? "" : title.toString())
                        + (current.subtitle == null ? "" : ", " + current.subtitle));
                updateTick();
            } catch (Throwable t) {
                state.fail(t);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (h != oldh) {
                post(() -> {
                    if (!state.restored) Refl.call(state.row, "notifyHeightChanged", "island live card size", false);
                });
            }
        }

        private void updateTick() {
            removeCallbacks(tick);
            if (!state.restored && current != null && isAttachedToWindow() && isShown()
                    && getWindowVisibility() == VISIBLE && (current.trailing == IslandItem.Trailing.CHRONOMETER
                    || dev.flexy.dynamicisland.island.IslandView.hasRunningMetric(current)
                    || current.kind == IslandItem.Kind.TIMER || current.kind == IslandItem.Kind.RECORDING)) {
                if ((current.recorder && !current.recorderSaved && (current.trailing == IslandItem.Trailing.CHRONOMETER || dev.flexy.dynamicisland.island.VoiceLevels.get().live(SystemClock.uptimeMillis()))) || (current.chronoMillis && current.trailing == IslandItem.Trailing.CHRONOMETER)) {
                    postOnAnimation(tick);
                } else {
                    postDelayed(tick, 1000 - SystemClock.uptimeMillis() % 1000);
                }
            }
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateTick();
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (renderer != null) updateTick();
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            super.onWindowVisibilityChanged(visibility);
            if (renderer != null) updateTick();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            try {
                if (torchTracking) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_MOVE) {
                        if (renderer.shadeTorchMove(event.getX())) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        torchTracking = false;
                        renderer.shadeTorchEnd();
                    }
                    postInvalidateOnAnimation();
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    downItem = item(key);
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    if (downItem != null && downItem.torch && renderer.shadeTorchStart(downItem, getWidth(), downX, downY)) {
                        torchTracking = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        postInvalidateOnAnimation();
                        return true;
                    }
                    tracking = downItem != null && downItem.remoteView == null
                            && renderer.shadeCardHasButton(downItem, getWidth(), downX, downY);
                    if (!tracking) return false;
                    renderer.shadeButtonDown(downItem, getWidth(), downX, downY);
                    postInvalidateOnAnimation();
                }
                if (!tracking) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_MOVE
                        && (Math.abs(event.getX() - downX) > slop || Math.abs(event.getY() - downY) > slop)) {
                    moved = true;
                    cancelLongPress();
                    setPressed(false);
                    renderer.shadeButtonUp();
                    postInvalidateOnAnimation();
                }
                if (event.getPointerCount() > 1) {
                    moved = true;
                    cancelLongPress();
                }
                boolean handled = super.onTouchEvent(event);
                if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    tracking = false;
                    renderer.shadeButtonUp();
                    postInvalidateOnAnimation();
                }
                return handled;
            } catch (Throwable t) {
                state.fail(t);
                return false;
            }
        }
    }
}
