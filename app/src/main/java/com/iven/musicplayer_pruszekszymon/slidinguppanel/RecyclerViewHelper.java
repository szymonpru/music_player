package com.iven.musicplayer_pruszekszymon.slidinguppanel;

import android.support.v7.widget.RecyclerView;
import android.view.View;

class RecyclerViewHelper {
    int getRecyclerViewScrollPosition(RecyclerView recyclerView, boolean isSlidingUp) {
        if (recyclerView == null) return 0;
        if (recyclerView.getChildCount() > 0) {
            RecyclerView.LayoutManager lm = recyclerView.getLayoutManager();
            if (recyclerView.getAdapter() == null) return 0;
            if (isSlidingUp) {
                View firstChild = recyclerView.getChildAt(0);
                // Approximate the scroll position based on the top child and the first visible item
                return recyclerView.getChildLayoutPosition(firstChild) * lm.getDecoratedMeasuredHeight(firstChild) - lm.getDecoratedTop(firstChild);
            } else {
                View lastChild = recyclerView.getChildAt(recyclerView.getChildCount() - 1);
                // Approximate the scroll position based on the bottom child and the last visible item
                return (recyclerView.getAdapter().getItemCount() - 1) * lm.getDecoratedMeasuredHeight(lastChild) + lm.getDecoratedBottom(lastChild) - recyclerView.getBottom();
            }
        } else {
            return 0;
        }
    }
}
