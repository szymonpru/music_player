package com.iven.musicplayer_pruszekszymon.slidinguppanel;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.widget.OverScroller;

import java.util.Arrays;

class ViewDragHelper {

    static final int STATE_IDLE = 0;

    static final int STATE_SETTLING = 2;

    private static final int INVALID_POINTER = -1;

    private static final int STATE_DRAGGING = 1;

    private static final int EDGE_LEFT = 1;

    private static final int EDGE_RIGHT = 1 << 1;

    private static final int EDGE_TOP = 1 << 2;

    private static final int EDGE_BOTTOM = 1 << 3;

    private static final int EDGE_SIZE = 20; // dp

    private static final int BASE_SETTLE_DURATION = 256; // ms
    private static final int MAX_SETTLE_DURATION = 600; // ms

    private static final Interpolator sInterpolator = new Interpolator() {
        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * t * t * t + 1.0f;
        }
    };
    private final Callback mCallback;
    private final ViewGroup mParentView;
    // Current drag state; idle, dragging or settling
    private int mDragState;
    // Distance to travel before a drag may begin
    private int mTouchSlop;
    // Last known position/pointer tracking
    private int mActivePointerId = INVALID_POINTER;
    private float[] mInitialMotionX;
    private float[] mInitialMotionY;
    private float[] mLastMotionX;
    private float[] mLastMotionY;
    private int[] mInitialEdgesTouched;
    private int[] mEdgeDragsInProgress;
    private int[] mEdgeDragsLocked;
    private VelocityTracker mVelocityTracker;
    private float mMaxVelocity;
    private float mMinVelocity;
    private int mEdgeSize;
    private OverScroller mScroller;
    private View mCapturedView;
    private final Runnable mSetIdleRunnable = new Runnable() {
        public void run() {
            setDragState(STATE_IDLE);
        }
    };
    private boolean mReleaseInProgress;

    private ViewDragHelper(Context context, ViewGroup forParent, Interpolator interpolator, Callback cb) {
        if (forParent == null) {
            throw new IllegalArgumentException("Parent view may not be null");
        }
        if (cb == null) {
            throw new IllegalArgumentException("Callback may not be null");
        }

        mParentView = forParent;
        mCallback = cb;

        final ViewConfiguration vc = ViewConfiguration.get(context);
        final float density = context.getResources().getDisplayMetrics().density;
        mEdgeSize = (int) (EDGE_SIZE * density + 0.5f);

        mTouchSlop = vc.getScaledTouchSlop();
        mMaxVelocity = vc.getScaledMaximumFlingVelocity();
        mMinVelocity = vc.getScaledMinimumFlingVelocity();
        mScroller = new OverScroller(context, interpolator != null ? interpolator : sInterpolator);
    }

    static ViewDragHelper create(ViewGroup forParent, Callback cb) {
        return new ViewDragHelper(forParent.getContext(), forParent, null, cb);
    }

    void setMinVelocity(float minVel) {
        mMinVelocity = minVel;
    }

    int getViewDragState() {
        return mDragState;
    }

    private void captureChildView(View childView, int activePointerId) {
        if (childView.getParent() != mParentView) {
            throw new IllegalArgumentException("captureChildView: parameter must be a descendant " +
                    "of the ViewDragHelper's tracked parent view (" + mParentView + ")");
        }

        mCapturedView = childView;
        mActivePointerId = activePointerId;
        mCallback.onViewCaptured(childView, activePointerId);
        setDragState(STATE_DRAGGING);
    }

    int getTouchSlop() {
        return mTouchSlop;
    }

    void cancel() {
        mActivePointerId = INVALID_POINTER;
        clearMotionHistory();

        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }

    void abort() {
        cancel();
        if (mDragState == STATE_SETTLING) {
            final int oldX = mScroller.getCurrX();
            final int oldY = mScroller.getCurrY();
            mScroller.abortAnimation();
            final int newX = mScroller.getCurrX();
            final int newY = mScroller.getCurrY();
            mCallback.onViewPositionChanged(mCapturedView, newX, newY, newX - oldX, newY - oldY);
        }
        setDragState(STATE_IDLE);
    }

    boolean smoothSlideViewTo(View child, int finalLeft, int finalTop) {
        mCapturedView = child;
        mActivePointerId = INVALID_POINTER;

        return forceSettleCapturedViewAt(finalLeft, finalTop, 0, 0);
    }

    boolean settleCapturedViewAt(int finalLeft, int finalTop) {
        if (!mReleaseInProgress) {
            throw new IllegalStateException("Cannot settleCapturedViewAt outside of a call to " +
                    "Callback#onViewReleased");
        }

        return forceSettleCapturedViewAt(finalLeft, finalTop,
                (int) mVelocityTracker.getXVelocity(mActivePointerId),
                (int) mVelocityTracker.getYVelocity(mActivePointerId));
    }

    private boolean forceSettleCapturedViewAt(int finalLeft, int finalTop, int xVel, int yVel) {
        final int startLeft = mCapturedView.getLeft();
        final int startTop = mCapturedView.getTop();
        final int dx = finalLeft - startLeft;
        final int dy = finalTop - startTop;

        if (dx == 0 && dy == 0) {
            // Nothing to do. Send callbacks, be done.
            mScroller.abortAnimation();
            setDragState(STATE_IDLE);
            return false;
        }

        final int duration = computeSettleDuration(mCapturedView, dx, dy, xVel, yVel);
        mScroller.startScroll(startLeft, startTop, dx, dy, duration);

        setDragState(STATE_SETTLING);
        return true;
    }

    private int computeSettleDuration(View child, int dx, int dy, int xVel, int yVel) {
        xVel = clampMag(xVel, (int) mMinVelocity, (int) mMaxVelocity);
        yVel = clampMag(yVel, (int) mMinVelocity, (int) mMaxVelocity);
        final int absDx = Math.abs(dx);
        final int absDy = Math.abs(dy);
        final int absXVel = Math.abs(xVel);
        final int absYVel = Math.abs(yVel);
        final int addedVel = absXVel + absYVel;
        final int addedDistance = absDx + absDy;

        final float xWeight = xVel != 0 ? (float) absXVel / addedVel :
                (float) absDx / addedDistance;
        final float yWeight = yVel != 0 ? (float) absYVel / addedVel :
                (float) absDy / addedDistance;

        int xDuration = computeAxisDuration(dx, xVel, mCallback.getViewHorizontalDragRange());
        int yDuration = computeAxisDuration(dy, yVel, mCallback.getViewVerticalDragRange(child));

        return (int) (xDuration * xWeight + yDuration * yWeight);
    }

    private int computeAxisDuration(int delta, int velocity, int motionRange) {
        if (delta == 0) {
            return 0;
        }

        final int width = mParentView.getWidth();
        final int halfWidth = width / 2;
        final float distanceRatio = Math.min(1f, (float) Math.abs(delta) / width);
        final float distance = halfWidth + halfWidth *
                distanceInfluenceForSnapDuration(distanceRatio);

        int duration;
        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration = 4 * Math.round(1000 * Math.abs(distance / velocity));
        } else {
            final float range = (float) Math.abs(delta) / motionRange;
            duration = (int) ((range + 1) * BASE_SETTLE_DURATION);
        }
        return Math.min(duration, MAX_SETTLE_DURATION);
    }

    private int clampMag(int value, int absMin, int absMax) {
        final int absValue = Math.abs(value);
        if (absValue < absMin) return 0;
        if (absValue > absMax) return value > 0 ? absMax : -absMax;
        return value;
    }

    private float clampMag(float value, float absMin, float absMax) {
        final float absValue = Math.abs(value);
        if (absValue < absMin) return 0;
        if (absValue > absMax) return value > 0 ? absMax : -absMax;
        return value;
    }

    private float distanceInfluenceForSnapDuration(float f) {
        f -= 0.5f; // center the values about 0.
        f *= 0.3f * Math.PI / 2.0f;
        return (float) Math.sin(f);
    }

    boolean continueSettling(boolean deferCallbacks) {
        // Make sure, there is a captured view
        if (mCapturedView == null) {
            return false;
        }
        if (mDragState == STATE_SETTLING) {
            boolean keepGoing = mScroller.computeScrollOffset();
            final int x = mScroller.getCurrX();
            final int y = mScroller.getCurrY();
            final int dx = x - mCapturedView.getLeft();
            final int dy = y - mCapturedView.getTop();

            if (!keepGoing && dy != 0) { //fix #525
                //Invalid drag state
                mCapturedView.setTop(0);
                return true;
            }

            if (dx != 0) {
                mCapturedView.offsetLeftAndRight(dx);
            }
            if (dy != 0) {
                mCapturedView.offsetTopAndBottom(dy);
            }

            if (dx != 0 || dy != 0) {
                mCallback.onViewPositionChanged(mCapturedView, x, y, dx, dy);
            }

            if (keepGoing && x == mScroller.getFinalX() && y == mScroller.getFinalY()) {
                // Close enough. The interpolator/scroller might think we're still moving
                // but the user sure doesn't.
                mScroller.abortAnimation();
                keepGoing = mScroller.isFinished();
            }

            if (!keepGoing) {
                if (deferCallbacks) {
                    mParentView.post(mSetIdleRunnable);
                } else {
                    setDragState(STATE_IDLE);
                }
            }
        }

        return mDragState == STATE_SETTLING;
    }

    private void dispatchViewReleased(float xVel, float yVel) {
        mReleaseInProgress = true;
        mCallback.onViewReleased(mCapturedView, xVel, yVel);
        mReleaseInProgress = false;

        if (mDragState == STATE_DRAGGING) {
            // onViewReleased didn't call a method that would have changed this. Go idle.
            setDragState(STATE_IDLE);
        }
    }

    private void clearMotionHistory() {
        if (mInitialMotionX == null) {
            return;
        }
        Arrays.fill(mInitialMotionX, 0);
        Arrays.fill(mInitialMotionY, 0);
        Arrays.fill(mLastMotionX, 0);
        Arrays.fill(mLastMotionY, 0);
        Arrays.fill(mInitialEdgesTouched, 0);
        Arrays.fill(mEdgeDragsInProgress, 0);
        Arrays.fill(mEdgeDragsLocked, 0);
    }

    private void clearMotionHistory(int pointerId) {
        if (mInitialMotionX == null || mInitialMotionX.length <= pointerId) {
            return;
        }
        mInitialMotionX[pointerId] = 0;
        mInitialMotionY[pointerId] = 0;
        mLastMotionX[pointerId] = 0;
        mLastMotionY[pointerId] = 0;
        mInitialEdgesTouched[pointerId] = 0;
        mEdgeDragsInProgress[pointerId] = 0;
        mEdgeDragsLocked[pointerId] = 0;
    }

    private void ensureMotionHistorySizeForId(int pointerId) {
        if (mInitialMotionX == null || mInitialMotionX.length <= pointerId) {
            float[] imx = new float[pointerId + 1];
            float[] imy = new float[pointerId + 1];
            float[] lmx = new float[pointerId + 1];
            float[] lmy = new float[pointerId + 1];
            int[] iit = new int[pointerId + 1];
            int[] eDip = new int[pointerId + 1];
            int[] edl = new int[pointerId + 1];

            if (mInitialMotionX != null) {
                System.arraycopy(mInitialMotionX, 0, imx, 0, mInitialMotionX.length);
                System.arraycopy(mInitialMotionY, 0, imy, 0, mInitialMotionY.length);
                System.arraycopy(mLastMotionX, 0, lmx, 0, mLastMotionX.length);
                System.arraycopy(mLastMotionY, 0, lmy, 0, mLastMotionY.length);
                System.arraycopy(mInitialEdgesTouched, 0, iit, 0, mInitialEdgesTouched.length);
                System.arraycopy(mEdgeDragsInProgress, 0, eDip, 0, mEdgeDragsInProgress.length);
                System.arraycopy(mEdgeDragsLocked, 0, edl, 0, mEdgeDragsLocked.length);
            }

            mInitialMotionX = imx;
            mInitialMotionY = imy;
            mLastMotionX = lmx;
            mLastMotionY = lmy;
            mInitialEdgesTouched = iit;
            mEdgeDragsInProgress = eDip;
            mEdgeDragsLocked = edl;
        }
    }

    private void saveInitialMotion(float x, float y, int pointerId) {
        ensureMotionHistorySizeForId(pointerId);
        mInitialMotionX[pointerId] = mLastMotionX[pointerId] = x;
        mInitialMotionY[pointerId] = mLastMotionY[pointerId] = y;
        mInitialEdgesTouched[pointerId] = getEdgesTouched((int) x, (int) y);
    }

    private void saveLastMotion(MotionEvent ev) {
        final int pointerCount = ev.getPointerCount();
        for (int i = 0; i < pointerCount; i++) {
            final int pointerId = ev.getPointerId(i);
            final float x = ev.getX(i);
            final float y = ev.getY(i);
            // Sometimes we can try and save last motion for a pointer never recorded in initial motion. In this case we just discard it.
            if (mLastMotionX != null && mLastMotionY != null
                    && mLastMotionX.length > pointerId && mLastMotionY.length > pointerId) {
                mLastMotionX[pointerId] = x;
                mLastMotionY[pointerId] = y;
            }
        }
    }

    void setDragState(int state) {
        if (mDragState != state) {
            mDragState = state;
            mCallback.onViewDragStateChanged(state);
            if (mDragState == STATE_IDLE) {
                mCapturedView = null;
            }
        }
    }

    private boolean tryCaptureViewForDrag(View toCapture, int pointerId) {
        if (toCapture == mCapturedView && mActivePointerId == pointerId) {
            // Already done!
            return true;
        }
        if (toCapture != null && mCallback.tryCaptureView(toCapture, pointerId)) {
            mActivePointerId = pointerId;
            captureChildView(toCapture, pointerId);
            return true;
        }
        return false;
    }

    boolean shouldInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        final int actionIndex = ev.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN) {
            // Reset things for a new event stream, just in case we didn't get
            // the whole previous stream.
            cancel();
        }

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                final float x = ev.getX();
                final float y = ev.getY();
                final int pointerId = ev.getPointerId(0);
                saveInitialMotion(x, y, pointerId);

                final View toCapture = findTopChildUnder((int) x, (int) y);

                // Catch a settling view if possible.
                if (toCapture == mCapturedView && mDragState == STATE_SETTLING) {
                    tryCaptureViewForDrag(toCapture, pointerId);
                }

                final int edgesTouched = mInitialEdgesTouched[pointerId];
                if ((edgesTouched) != 0) {
                    mCallback.onEdgeTouched();
                }
                break;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                final int pointerId = ev.getPointerId(actionIndex);
                final float x = ev.getX(actionIndex);
                final float y = ev.getY(actionIndex);

                saveInitialMotion(x, y, pointerId);

                // A ViewDragHelper can only manipulate one view at a time.
                if (mDragState == STATE_IDLE) {
                    final int edgesTouched = mInitialEdgesTouched[pointerId];
                    if ((edgesTouched) != 0) {
                        mCallback.onEdgeTouched();
                    }
                } else if (mDragState == STATE_SETTLING) {
                    // Catch a settling view if possible.
                    final View toCapture = findTopChildUnder((int) x, (int) y);
                    if (toCapture == mCapturedView) {
                        tryCaptureViewForDrag(toCapture, pointerId);
                    }
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                // First to cross a touch slop over a draggable view wins. Also report edge drags.
                final int pointerCount = ev.getPointerCount();
                for (int i = 0; i < pointerCount && mInitialMotionX != null && mInitialMotionY != null; i++) {
                    final int pointerId = ev.getPointerId(i);
                    if (pointerId >= mInitialMotionX.length || pointerId >= mInitialMotionY.length) {
                        continue;
                    }
                    final float x = ev.getX(i);
                    final float y = ev.getY(i);
                    final float dx = x - mInitialMotionX[pointerId];
                    final float dy = y - mInitialMotionY[pointerId];

                    reportNewEdgeDrags(dx, dy, pointerId);
                    if (mDragState == STATE_DRAGGING) {
                        // Callback might have started an edge drag
                        break;
                    }

                    final View toCapture = findTopChildUnder((int) mInitialMotionX[pointerId], (int) mInitialMotionY[pointerId]);
                    if (toCapture != null && checkTouchSlop(toCapture, dx, dy) &&
                            tryCaptureViewForDrag(toCapture, pointerId)) {
                        break;
                    }
                }
                saveLastMotion(ev);
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerId = ev.getPointerId(actionIndex);
                clearMotionHistory(pointerId);
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                cancel();
                break;
            }
        }

        return mDragState == STATE_DRAGGING;
    }

    void processTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        final int actionIndex = ev.getActionIndex();

        if (action == MotionEvent.ACTION_DOWN) {
            // Reset things for a new event stream, just in case we didn't get
            // the whole previous stream.
            cancel();
        }

        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                final float x = ev.getX();
                final float y = ev.getY();
                final int pointerId = ev.getPointerId(0);
                final View toCapture = findTopChildUnder((int) x, (int) y);

                saveInitialMotion(x, y, pointerId);

                // Since the parent is already directly processing this touch event,
                // there is no reason to delay for a slop before dragging.
                // Start immediately if possible.
                tryCaptureViewForDrag(toCapture, pointerId);

                final int edgesTouched = mInitialEdgesTouched[pointerId];
                if ((edgesTouched) != 0) {
                    mCallback.onEdgeTouched();
                }
                break;
            }

            case MotionEvent.ACTION_POINTER_DOWN: {
                final int pointerId = ev.getPointerId(actionIndex);
                final float x = ev.getX(actionIndex);
                final float y = ev.getY(actionIndex);

                saveInitialMotion(x, y, pointerId);

                // A ViewDragHelper can only manipulate one view at a time.
                if (mDragState == STATE_IDLE) {
                    // If we're idle we can do anything! Treat it like a normal down event.

                    final View toCapture = findTopChildUnder((int) x, (int) y);
                    tryCaptureViewForDrag(toCapture, pointerId);

                    final int edgesTouched = mInitialEdgesTouched[pointerId];
                    if ((edgesTouched) != 0) {
                        mCallback.onEdgeTouched();
                    }
                } else if (isCapturedViewUnder((int) x, (int) y)) {
                    // We're still tracking a captured view. If the same view is under this
                    // point, we'll swap to controlling it with this pointer instead.
                    // (This will still work if we're "catching" a settling view.)

                    tryCaptureViewForDrag(mCapturedView, pointerId);
                }
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                if (mDragState == STATE_DRAGGING) {

                    final int index = ev.findPointerIndex(mActivePointerId);
                    final float x = ev.getX(index);
                    final float y = ev.getY(index);
                    final int idx = (int) (x - mLastMotionX[mActivePointerId]);
                    final int idy = (int) (y - mLastMotionY[mActivePointerId]);

                    dragTo(mCapturedView.getLeft() + idx, mCapturedView.getTop() + idy, idx, idy);

                    saveLastMotion(ev);
                } else {
                    // Check to see if any pointer is now over a draggable view.
                    final int pointerCount = ev.getPointerCount();
                    for (int i = 0; i < pointerCount; i++) {
                        final int pointerId = ev.getPointerId(i);
                        final float x = ev.getX(i);
                        final float y = ev.getY(i);
                        final float dx = x - mInitialMotionX[pointerId];
                        final float dy = y - mInitialMotionY[pointerId];

                        reportNewEdgeDrags(dx, dy, pointerId);
                        if (mDragState == STATE_DRAGGING) {
                            // Callback might have started an edge drag.
                            break;
                        }

                        final View toCapture = findTopChildUnder((int) mInitialMotionX[pointerId], (int) mInitialMotionY[pointerId]);
                        if (checkTouchSlop(toCapture, dx, dy) &&
                                tryCaptureViewForDrag(toCapture, pointerId)) {
                            break;
                        }
                    }
                    saveLastMotion(ev);
                }
                break;
            }

            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerId = ev.getPointerId(actionIndex);
                if (mDragState == STATE_DRAGGING && pointerId == mActivePointerId) {
                    // Try to find another pointer that's still holding on to the captured view.
                    int newActivePointer = INVALID_POINTER;
                    final int pointerCount = ev.getPointerCount();
                    for (int i = 0; i < pointerCount; i++) {
                        final int id = ev.getPointerId(i);
                        if (id == mActivePointerId) {
                            // This one's going away, skip.
                            continue;
                        }

                        final float x = ev.getX(i);
                        final float y = ev.getY(i);
                        if (findTopChildUnder((int) x, (int) y) == mCapturedView &&
                                tryCaptureViewForDrag(mCapturedView, id)) {
                            newActivePointer = mActivePointerId;
                            break;
                        }
                    }

                    if (newActivePointer == INVALID_POINTER) {
                        // We didn't find another pointer still touching the view, release it.
                        releaseViewForPointerUp();
                    }
                }
                clearMotionHistory(pointerId);
                break;
            }

            case MotionEvent.ACTION_UP: {
                if (mDragState == STATE_DRAGGING) {
                    releaseViewForPointerUp();
                }
                cancel();
                break;
            }

            case MotionEvent.ACTION_CANCEL: {
                if (mDragState == STATE_DRAGGING) {
                    dispatchViewReleased(0, 0);
                }
                cancel();
                break;
            }
        }
    }

    private void reportNewEdgeDrags(float dx, float dy, int pointerId) {
        int dragsStarted = 0;
        if (checkNewEdgeDrag(dx, dy, pointerId, EDGE_LEFT)) {
            dragsStarted |= EDGE_LEFT;
        }
        if (checkNewEdgeDrag(dy, dx, pointerId, EDGE_TOP)) {
            dragsStarted |= EDGE_TOP;
        }
        if (checkNewEdgeDrag(dx, dy, pointerId, EDGE_RIGHT)) {
            dragsStarted |= EDGE_RIGHT;
        }
        if (checkNewEdgeDrag(dy, dx, pointerId, EDGE_BOTTOM)) {
            dragsStarted |= EDGE_BOTTOM;
        }

        if (dragsStarted != 0) {
            mEdgeDragsInProgress[pointerId] |= dragsStarted;
            mCallback.onEdgeDragStarted();
        }
    }

    private boolean checkNewEdgeDrag(float delta, float oDelta, int pointerId, int edge) {
        final float absDelta = Math.abs(delta);
        final float absODelta = Math.abs(oDelta);

        if ((mInitialEdgesTouched[pointerId] & edge) != edge || edge == 0 ||
                (mEdgeDragsLocked[pointerId] & edge) == edge ||
                (mEdgeDragsInProgress[pointerId] & edge) == edge ||
                (absDelta <= mTouchSlop && absODelta <= mTouchSlop)) {
            return false;
        }
        if (absDelta < absODelta * 0.5f && mCallback.onEdgeLock()) {
            mEdgeDragsLocked[pointerId] |= edge;
            return false;
        }
        return (mEdgeDragsInProgress[pointerId] & edge) == 0 && absDelta > mTouchSlop;
    }

    private boolean checkTouchSlop(View child, float dx, float dy) {
        if (child == null) {
            return false;
        }
        final boolean checkHorizontal = mCallback.getViewHorizontalDragRange() > 0;
        final boolean checkVertical = mCallback.getViewVerticalDragRange(child) > 0;

        if (checkHorizontal && checkVertical) {
            return dx * dx + dy * dy > mTouchSlop * mTouchSlop;
        } else if (checkHorizontal) {
            return Math.abs(dx) > mTouchSlop;
        } else if (checkVertical) {
            return Math.abs(dy) > mTouchSlop;
        }
        return false;
    }

    boolean isDragging() {
        return mDragState == STATE_DRAGGING;
    }

    private void releaseViewForPointerUp() {
        mVelocityTracker.computeCurrentVelocity(1000, mMaxVelocity);
        final float xVel = clampMag(
                mVelocityTracker.getXVelocity(mActivePointerId),
                mMinVelocity, mMaxVelocity);
        final float yVel = clampMag(
                mVelocityTracker.getYVelocity(mActivePointerId),
                mMinVelocity, mMaxVelocity);
        dispatchViewReleased(xVel, yVel);
    }

    private void dragTo(int left, int top, int dx, int dy) {
        int clampedX = left;
        int clampedY = top;
        final int oldLeft = mCapturedView.getLeft();
        final int oldTop = mCapturedView.getTop();
        if (dx != 0) {
            clampedX = mCallback.clampViewPositionHorizontal();
            mCapturedView.offsetLeftAndRight(clampedX - oldLeft);
        }
        if (dy != 0) {
            clampedY = mCallback.clampViewPositionVertical(mCapturedView, top, dy);
            mCapturedView.offsetTopAndBottom(clampedY - oldTop);
        }

        if (dx != 0 || dy != 0) {
            final int clampedDx = clampedX - oldLeft;
            final int clampedDy = clampedY - oldTop;
            mCallback.onViewPositionChanged(mCapturedView, clampedX, clampedY,
                    clampedDx, clampedDy);
        }
    }

    private boolean isCapturedViewUnder(int x, int y) {
        return isViewUnder(mCapturedView, x, y);
    }

    private boolean isViewUnder(View view, int x, int y) {
        return view != null && x >= view.getLeft() && x < view.getRight() && y >= view.getTop() && y < view.getBottom();
    }

    private View findTopChildUnder(int x, int y) {
        final int childCount = mParentView.getChildCount();
        for (int i = childCount - 1; i >= 0; i--) {
            final View child = mParentView.getChildAt(mCallback.getOrderedChildIndex(i));
            if (x >= child.getLeft() && x < child.getRight() &&
                    y >= child.getTop() && y < child.getBottom()) {
                return child;
            }
        }
        return null;
    }

    private int getEdgesTouched(int x, int y) {
        int result = 0;

        if (x < mParentView.getLeft() + mEdgeSize) result |= EDGE_LEFT;
        if (y < mParentView.getTop() + mEdgeSize) result |= EDGE_TOP;
        if (x > mParentView.getRight() - mEdgeSize) result |= EDGE_RIGHT;
        if (y > mParentView.getBottom() - mEdgeSize) result |= EDGE_BOTTOM;

        return result;
    }

    public static abstract class Callback {

        public void onViewDragStateChanged(int state) {
        }


        public void onViewPositionChanged(View changedView, int left, int top, int dx, int dy) {
        }


        public void onViewCaptured(View capturedChild, int activePointerId) {
        }

        public void onViewReleased(View releasedChild, float xVel, float yVel) {
        }


        void onEdgeTouched() {
        }

        boolean onEdgeLock() {
            return false;
        }


        void onEdgeDragStarted() {
        }


        int getOrderedChildIndex(int index) {
            return index;
        }

        int getViewHorizontalDragRange() {
            return 0;
        }


        public int getViewVerticalDragRange(View child) {
            return 0;
        }

        public abstract boolean tryCaptureView(View child, int pointerId);

        int clampViewPositionHorizontal() {
            return 0;
        }

        public int clampViewPositionVertical(View child, int top, int dy) {
            return 0;
        }
    }
}
