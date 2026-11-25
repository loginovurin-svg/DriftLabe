package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;

/**
 * Keeps the terminal drawer compatible with the strict measurement check in
 * AndroidX DrawerLayout. RelativeLayout can pass a finite AT_MOST spec to a
 * match-parent child while resolving rules such as below/above; that is still
 * a valid size for this fixed terminal zone, so promote it to EXACTLY before
 * DrawerLayout measures its children.
 */
public final class TermuxDrawerLayout extends DrawerLayout {

    public TermuxDrawerLayout(Context context) {
        super(context);
    }

    public TermuxDrawerLayout(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public TermuxDrawerLayout(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
            toExactSpec(widthMeasureSpec, getSuggestedMinimumWidth()),
            toExactSpec(heightMeasureSpec, getSuggestedMinimumHeight())
        );
    }

    private int toExactSpec(int measureSpec, int minimumSize) {
        int mode = MeasureSpec.getMode(measureSpec);
        if (mode == MeasureSpec.EXACTLY) return measureSpec;

        // AT_MOST carries the exact finite zone size that DrawerLayout should fill.
        int size = MeasureSpec.getSize(measureSpec);
        if (mode == MeasureSpec.AT_MOST) {
            return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
        }

        // The terminal host is finite in the activity layout. Keep a safe
        // deterministic fallback if a future parent ever supplies UNSPECIFIED.
        return MeasureSpec.makeMeasureSpec(Math.max(0, minimumSize), MeasureSpec.EXACTLY);
    }
}