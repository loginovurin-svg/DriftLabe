package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser-like session tabs for the blue terminal zone.
 *
 * <p>The view is intentionally only a presentation/control layer. Session
 * creation, switching and termination remain in
 * {@link TermuxTerminalSessionActivityClient} and {@link com.termux.app.TermuxService}.</p>
 */
public final class TermuxSessionTabsView extends HorizontalScrollView {

    private static final int TAB_MIN_WIDTH_DP = 92;
    private static final int CLOSE_BUTTON_WIDTH_DP = 34;
    private static final int PLUS_BUTTON_WIDTH_DP = 48;

    private final LinearLayout mTabsContainer;
    private TermuxActivity mActivity;

    public TermuxSessionTabsView(Context context) {
        this(context, null);
    }

    public TermuxSessionTabsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setBackgroundColor(Color.rgb(13, 17, 23));

        mTabsContainer = new LinearLayout(context);
        mTabsContainer.setOrientation(LinearLayout.HORIZONTAL);
        mTabsContainer.setGravity(Gravity.CENTER_VERTICAL);
        addView(mTabsContainer, new HorizontalScrollView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void bind(@NonNull TermuxActivity activity) {
        mActivity = activity;
        refresh();
    }

    /**
     * Rebuild the compact strip from the service's live session list.
     * The list is copied so a session exit callback cannot mutate it while
     * this view is laying out children.
     */
    public void refresh() {
        mTabsContainer.removeAllViews();
        if (mActivity == null || mActivity.getTermuxService() == null) return;

        List<TermuxSession> sessions = new ArrayList<>(mActivity.getTermuxService().getTermuxSessions());
        TerminalSession activeSession = mActivity.getCurrentSession();
        View activeTab = null;

        for (TermuxSession termuxSession : sessions) {
            if (termuxSession == null || termuxSession.getTerminalSession() == null) continue;

            TerminalSession session = termuxSession.getTerminalSession();
            View tab = createTab(session, session == activeSession);
            mTabsContainer.addView(tab);
            if (session == activeSession) activeTab = tab;
        }

        TextView plus = new TextView(getContext());
        plus.setText("+");
        plus.setTextColor(Color.WHITE);
        plus.setTextSize(24);
        plus.setGravity(Gravity.CENTER);
        plus.setMinHeight(dp(44));
        plus.setMinWidth(dp(PLUS_BUTTON_WIDTH_DP));
        plus.setContentDescription("Создать новую сессию");
        plus.setBackground(createBackground(false, true));
        plus.setOnClickListener(v -> {
            if (mActivity != null)
                mActivity.getTermuxTerminalSessionClient().addNewSession(false, null);
        });
        mTabsContainer.addView(plus);

        if (activeTab != null) {
            View tabToReveal = activeTab;
            post(() -> revealTab(tabToReveal));
        }
    }

    private View createTab(@NonNull TerminalSession session, boolean active) {
        LinearLayout tab = new LinearLayout(getContext());
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setMinimumWidth(dp(TAB_MIN_WIDTH_DP));
        tab.setMinimumHeight(dp(44));
        tab.setPadding(dp(10), 0, 0, 0);
        tab.setBackground(createBackground(active, false));
        tab.setContentDescription(sessionTabName(session));

        TextView title = new TextView(getContext());
        title.setText(sessionTabName(session));
        title.setTextColor(active ? Color.WHITE : Color.rgb(155, 164, 174));
        title.setTextSize(14);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setTypeface(android.graphics.Typeface.MONOSPACE);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        tab.addView(title);

        TextView close = new TextView(getContext());
        close.setText("×");
        close.setTextColor(active ? Color.WHITE : Color.rgb(155, 164, 174));
        close.setTextSize(19);
        close.setGravity(Gravity.CENTER);
        close.setMinWidth(dp(CLOSE_BUTTON_WIDTH_DP));
        close.setMinHeight(dp(44));
        close.setContentDescription("Закрыть " + sessionTabName(session));
        close.setOnClickListener(v -> {
            if (mActivity != null)
                mActivity.getTermuxTerminalSessionClient().closeSession(session);
        });
        // A long press on a tab or its close control is deliberately inert.
        close.setOnLongClickListener(v -> true);
        tab.addView(close);

        tab.setOnClickListener(v -> {
            if (mActivity != null)
                mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
        });
        tab.setOnLongClickListener(v -> true);
        return tab;
    }

    private String sessionTabName(@NonNull TerminalSession session) {
        return session.mSessionName == null || session.mSessionName.isEmpty()
            ? "bash"
            : session.mSessionName;
    }

    private GradientDrawable createBackground(boolean active, boolean plus) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(active ? Color.rgb(31, 52, 76) : Color.rgb(24, 30, 38));
        if (active) {
            background.setStroke(dp(1), Color.rgb(51, 151, 255));
        } else if (plus) {
            background.setStroke(dp(1), Color.rgb(64, 78, 96));
        }
        return background;
    }

    private void revealTab(@NonNull View tab) {
        Rect rect = new Rect(tab.getLeft(), tab.getTop(), tab.getRight(), tab.getBottom());
        int left = getScrollX();
        int right = left + getWidth();
        if (rect.right > right) {
            smoothScrollTo(rect.right - getWidth(), 0);
        } else if (rect.left < left) {
            smoothScrollTo(rect.left, 0);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}