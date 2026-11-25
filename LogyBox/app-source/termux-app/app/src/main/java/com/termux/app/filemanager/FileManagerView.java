package com.termux.app.filemanager;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.terminal.TerminalSession;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compact file browser for the left red zone.
 *
 * <p>The view deliberately keeps navigation one-way with the terminal: opening a
 * directory here sends {@code cd} to the already visible session. Reading the
 * terminal directory is explicit and uses the session's process cwd, so a
 * synchronization tap does not print a temporary command into the terminal.</p>
 */
public final class FileManagerView extends LinearLayout {

    private static final int HEADER_HEIGHT_DP = 32;
    private static final int ROW_HEIGHT_DP = 30;
    private static final int ACTION_ROW_HEIGHT_DP = 38;
    private static final int SEARCH_ROW_HEIGHT_DP = 38;
    private static final long DOUBLE_TAP_TIMEOUT_MS = 420L;

    private final TermuxActivity activity;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService directoryExecutor = Executors.newSingleThreadExecutor();
    private final FileListAdapter adapter = new FileListAdapter();
    private final TextView pathView;
    private final TextView statusView;
    private final ListView fileListView;
    private final LinearLayout actionRow;
    private final LinearLayout searchRow;
    private final EditText searchInput;

    private String currentDirectoryPath;
    private String pendingTerminalDirectory;
    private String lastTappedPath;
    private long lastTapTime;
    private long operationGeneration;
    private boolean searchMode;

    public FileManagerView(@NonNull TermuxActivity activity) {
        super(activity);
        this.activity = activity;
        setOrientation(VERTICAL);
        setBackgroundColor(Color.TRANSPARENT);
        setPadding(dp(3), dp(2), dp(3), dp(2));

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(HEADER_HEIGHT_DP)));

        TextView upButton = createHeaderButton("↑", R.string.file_manager_go_up);
        upButton.setOnClickListener(v -> goToParentDirectory());
        header.addView(upButton, new LinearLayout.LayoutParams(dp(26), dp(HEADER_HEIGHT_DP)));

        pathView = new TextView(activity);
        pathView.setTextColor(Color.rgb(255, 235, 235));
        pathView.setTextSize(9);
        pathView.setTypeface(Typeface.MONOSPACE);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        pathView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams pathParams = new LinearLayout.LayoutParams(0, dp(HEADER_HEIGHT_DP), 1f);
        pathParams.setMargins(dp(2), 0, dp(2), 0);
        header.addView(pathView, pathParams);

        TextView syncButton = createHeaderButton("⟳", R.string.file_manager_sync);
        syncButton.setOnClickListener(v -> synchronizeWithTerminal());
        header.addView(syncButton, new LinearLayout.LayoutParams(dp(28), dp(HEADER_HEIGHT_DP)));

        addView(header);

        fileListView = new ListView(activity);
        fileListView.setAdapter(adapter);
        fileListView.setDivider(new android.graphics.drawable.ColorDrawable(Color.argb(80, 255, 80, 80)));
        fileListView.setDividerHeight(1);
        fileListView.setCacheColorHint(Color.TRANSPARENT);
        fileListView.setBackgroundColor(Color.TRANSPARENT);
        fileListView.setPadding(0, 0, 0, 0);
        fileListView.setOnItemClickListener((parent, view, position, id) -> onItemTapped((FileItem) adapter.getItem(position)));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        addView(fileListView, listParams);

        statusView = new TextView(activity);
        statusView.setTextColor(Color.rgb(255, 190, 190));
        statusView.setTextSize(9);
        statusView.setTypeface(Typeface.MONOSPACE);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(3), dp(3), dp(3), dp(3));
        statusView.setSingleLine(false);
        addView(statusView, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        fileListView.setEmptyView(statusView);

        File home = TermuxConstants.TERMUX_HOME_DIR;
        currentDirectoryPath = home.getAbsolutePath();
        pathView.setText(currentDirectoryPath);

        actionRow = new LinearLayout(activity);
        actionRow.setOrientation(HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        TextView homeButton = createActionButton("⌂\nДомой", R.string.file_manager_home);
        homeButton.setOnClickListener(v -> goHome());
        actionRow.addView(homeButton, new LinearLayout.LayoutParams(0, dp(ACTION_ROW_HEIGHT_DP), 1f));

        TextView searchButton = createActionButton("⌕\nПоиск", R.string.file_manager_search);
        searchButton.setOnClickListener(v -> openSearchMode());
        actionRow.addView(searchButton, new LinearLayout.LayoutParams(0, dp(ACTION_ROW_HEIGHT_DP), 1f));
        addView(actionRow, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(ACTION_ROW_HEIGHT_DP)));

        searchRow = new LinearLayout(activity);
        searchRow.setOrientation(HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setVisibility(GONE);

        searchInput = new EditText(activity);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(10);
        searchInput.setTextColor(Color.WHITE);
        searchInput.setHintTextColor(Color.rgb(255, 190, 190));
        searchInput.setHint(R.string.file_manager_enter_name);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setPadding(dp(4), 0, dp(2), 0);
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enterPressed = event != null
                && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enterPressed) {
                startSearch();
                return true;
            }
            return false;
        });
        searchRow.addView(searchInput, new LinearLayout.LayoutParams(0, dp(SEARCH_ROW_HEIGHT_DP), 1f));

        TextView findButton = createCompactButton("Найти", R.string.file_manager_find);
        findButton.setOnClickListener(v -> startSearch());
        searchRow.addView(findButton, new LinearLayout.LayoutParams(dp(50), dp(SEARCH_ROW_HEIGHT_DP)));

        TextView cancelButton = createCompactButton("Отмена", R.string.file_manager_cancel_search);
        cancelButton.setOnClickListener(v -> closeSearchMode(true));
        searchRow.addView(cancelButton, new LinearLayout.LayoutParams(dp(58), dp(SEARCH_ROW_HEIGHT_DP)));
        addView(searchRow, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(SEARCH_ROW_HEIGHT_DP)));

        refresh();
    }

    /**
     * Reload the directory currently shown by the manager.
     */
    public void refresh() {
        loadDirectory(new File(currentDirectoryPath));
    }

    /**
     * Let the activity give the Android back action to the search controls first.
     *
     * @return true when search mode consumed the back action.
     */
    public boolean handleBackPressed() {
        if (!searchMode) return false;
        closeSearchMode(true);
        return true;
    }

    /**
     * Retry a navigation command after TermuxService has connected.
     */
    public void sendPendingDirectoryToTerminal() {
        if (pendingTerminalDirectory == null) return;
        sendDirectoryToTerminal(pendingTerminalDirectory);
    }

    /**
     * Stop the worker when the activity is permanently destroyed.
     */
    public void shutdown() {
        directoryExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void onItemTapped(@NonNull FileItem item) {
        if (item.searchResult) {
            File parent = new File(item.path).getParentFile();
            if (parent == null) {
                showToast(R.string.file_manager_directory_unreadable);
            } else {
                navigateToDirectory(parent, true);
            }
            return;
        }

        long now = System.currentTimeMillis();
        boolean isDoubleTap = item.directory
            && item.path.equals(lastTappedPath)
            && now - lastTapTime <= DOUBLE_TAP_TIMEOUT_MS;

        if (isDoubleTap) {
            lastTappedPath = null;
            lastTapTime = 0L;
            navigateToDirectory(new File(item.path), true);
            return;
        }

        lastTappedPath = item.directory ? item.path : null;
        lastTapTime = now;
    }

    private void goToParentDirectory() {
        File parent = new File(currentDirectoryPath).getParentFile();
        if (parent == null) {
            showToast(R.string.file_manager_at_root);
            return;
        }
        navigateToDirectory(parent, true);
    }

    private void goHome() {
        navigateToDirectory(TermuxConstants.TERMUX_HOME_DIR, true);
    }

    private void navigateToDirectory(@NonNull File directory, boolean sendToTerminal) {
        if (!directory.isDirectory()) {
            showToast(R.string.file_manager_directory_unreadable);
            return;
        }

        closeSearchMode(false);
        currentDirectoryPath = directory.getAbsolutePath();
        pathView.setText(currentDirectoryPath);
        if (sendToTerminal) {
            pendingTerminalDirectory = currentDirectoryPath;
            sendDirectoryToTerminal(currentDirectoryPath);
        }
        loadDirectory(directory);
    }

    private void loadDirectory(@NonNull File directory) {
        final String requestedPath = directory.getAbsolutePath();
        final long requestedOperation = ++operationGeneration;
        currentDirectoryPath = requestedPath;
        pathView.setText(requestedPath);
        statusView.setText(R.string.file_manager_loading);
        adapter.setItems(new ArrayList<>());

        directoryExecutor.execute(() -> {
            final List<FileItem> items = new ArrayList<>();
            String errorMessage = null;
            File[] files = directory.listFiles();

            if (files == null) {
                errorMessage = getResources().getString(R.string.file_manager_directory_unreadable);
            } else {
                Arrays.sort(files, DIRECTORY_FIRST_COMPARATOR);
                for (File file : files) {
                    items.add(new FileItem(file));
                }
                if (items.isEmpty()) {
                    errorMessage = getResources().getString(R.string.file_manager_empty);
                }
            }

            final String finalErrorMessage = errorMessage;
            mainHandler.post(() -> {
                if (requestedOperation != operationGeneration
                    || !requestedPath.equals(currentDirectoryPath)
                    || searchMode) return;
                adapter.setItems(items);
                statusView.setText(finalErrorMessage == null ? "" : finalErrorMessage);
            });
        });
    }

    private void openSearchMode() {
        searchMode = true;
        searchInput.setText("");
        actionRow.setVisibility(GONE);
        searchRow.setVisibility(VISIBLE);
        searchInput.requestFocus();
        InputMethodManager inputMethodManager = (InputMethodManager) activity.getSystemService(
            android.content.Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void closeSearchMode(boolean reloadDirectory) {
        if (!searchMode) return;
        searchMode = false;
        ++operationGeneration;
        searchRow.setVisibility(GONE);
        actionRow.setVisibility(VISIBLE);
        InputMethodManager inputMethodManager = (InputMethodManager) activity.getSystemService(
            android.content.Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
        if (reloadDirectory) {
            loadDirectory(new File(currentDirectoryPath));
        }
    }

    private void startSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            statusView.setText(R.string.file_manager_enter_name);
            showToast(R.string.file_manager_enter_name);
            return;
        }

        final long requestedOperation = ++operationGeneration;
        final String rootCanonicalPath = canonicalPath(TermuxConstants.TERMUX_HOME_DIR);
        final String queryLowerCase = query.toLowerCase(Locale.ROOT);
        statusView.setText(R.string.file_manager_searching);
        adapter.setItems(new ArrayList<>());

        directoryExecutor.execute(() -> {
            List<FileItem> results = new ArrayList<>();
            Set<String> visitedDirectories = new HashSet<>();
            searchRecursively(
                TermuxConstants.TERMUX_HOME_DIR,
                rootCanonicalPath,
                queryLowerCase,
                results,
                visitedDirectories);
            results.sort((left, right) -> left.path.toLowerCase(Locale.ROOT)
                .compareTo(right.path.toLowerCase(Locale.ROOT)));

            mainHandler.post(() -> {
                if (requestedOperation != operationGeneration || !searchMode) return;
                adapter.setItems(results);
                statusView.setText(results.isEmpty()
                    ? getResources().getString(R.string.file_manager_search_empty)
                    : "");
            });
        });
    }

    private void searchRecursively(
        @NonNull File directory,
        @NonNull String rootCanonicalPath,
        @NonNull String queryLowerCase,
        @NonNull List<FileItem> results,
        @NonNull Set<String> visitedDirectories) {
        String directoryCanonicalPath = canonicalPath(directory);
        if (!isInsideHome(directoryCanonicalPath, rootCanonicalPath)
            || !visitedDirectories.add(directoryCanonicalPath)) return;

        File[] children = directory.listFiles();
        if (children == null) return;
        Arrays.sort(children, DIRECTORY_FIRST_COMPARATOR);

        for (File child : children) {
            String childCanonicalPath = canonicalPath(child);
            if (!isInsideHome(childCanonicalPath, rootCanonicalPath)) continue;
            if (child.isDirectory()) {
                searchRecursively(
                    child,
                    rootCanonicalPath,
                    queryLowerCase,
                    results,
                    visitedDirectories);
            } else if (child.isFile()
                && child.getName().toLowerCase(Locale.ROOT).contains(queryLowerCase)) {
                results.add(new FileItem(child, true));
            }
        }
    }

    private static boolean isInsideHome(
        @NonNull String candidatePath,
        @NonNull String homeCanonicalPath) {
        return candidatePath.equals(homeCanonicalPath)
            || candidatePath.startsWith(homeCanonicalPath + File.separator);
    }

    private void synchronizeWithTerminal() {
        TerminalSession session = activity.getCurrentSession();
        if (session == null || !session.isRunning()) {
            showToast(R.string.file_manager_no_terminal_session);
            return;
        }

        String terminalPath = session.getCwd();
        if (terminalPath == null || terminalPath.length() == 0) {
            showToast(R.string.file_manager_sync_failed);
            return;
        }

        File terminalDirectory = new File(terminalPath);
        if (!terminalDirectory.isDirectory()) {
            showToast(R.string.file_manager_directory_unreadable);
            return;
        }

        pendingTerminalDirectory = null;
        closeSearchMode(false);
        currentDirectoryPath = terminalDirectory.getAbsolutePath();
        pathView.setText(currentDirectoryPath);
        loadDirectory(terminalDirectory);
        showToast(R.string.file_manager_synced);
    }

    private void sendDirectoryToTerminal(@NonNull String directoryPath) {
        TerminalSession session = activity.getCurrentSession();
        if (session == null || !session.isRunning()) return;

        session.write("cd " + shellQuote(directoryPath) + "\n");
        pendingTerminalDirectory = null;
    }

    private TextView createHeaderButton(@NonNull String label, int contentDescription) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(Color.rgb(255, 230, 230));
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setContentDescription(getResources().getString(contentDescription));
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackgroundResource(android.R.drawable.btn_default);
        return button;
    }

    private TextView createActionButton(@NonNull String label, int contentDescription) {
        TextView button = createCompactButton(label, contentDescription);
        button.setTextSize(10);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return button;
    }

    private TextView createCompactButton(@NonNull String label, int contentDescription) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(Color.rgb(255, 230, 230));
        button.setTextSize(10);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(getResources().getString(contentDescription));
        button.setClickable(true);
        button.setFocusable(true);
        button.setBackgroundResource(android.R.drawable.btn_default);
        return button;
    }

    private void showToast(int messageResId) {
        Toast.makeText(activity, messageResId, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String canonicalPath(@NonNull File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    private static String shellQuote(@NonNull String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final Comparator<File> DIRECTORY_FIRST_COMPARATOR = (left, right) -> {
        if (left.isDirectory() != right.isDirectory()) return left.isDirectory() ? -1 : 1;
        return left.getName().toLowerCase(Locale.ROOT)
            .compareTo(right.getName().toLowerCase(Locale.ROOT));
    };

    private static final class FileItem {
        final String path;
        final String name;
        final boolean directory;
        final boolean searchResult;

        FileItem(@NonNull File file) {
            this(file, false);
        }

        FileItem(@NonNull File file, boolean searchResult) {
            path = file.getAbsolutePath();
            name = file.getName().length() == 0 ? "/" : file.getName();
            directory = file.isDirectory();
            this.searchResult = searchResult;
        }
    }

    private final class FileListAdapter extends BaseAdapter {
        private final List<FileItem> items = new ArrayList<>();

        void setItems(@NonNull List<FileItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return items.get(position).path.hashCode();
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            FileItem item = items.get(position);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(ROW_HEIGHT_DP));
            row.setPadding(dp(2), 0, dp(1), 0);

            TextView icon = new TextView(activity);
            icon.setText(item.directory ? "▣" : iconForFile(item.name));
            icon.setTextColor(item.directory ? Color.rgb(255, 90, 90) : Color.rgb(255, 205, 205));
            icon.setTextSize(14);
            icon.setGravity(Gravity.CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(ROW_HEIGHT_DP)));

            TextView name = new TextView(activity);
            name.setText(item.searchResult ? item.path : item.name);
            name.setTextColor(item.directory ? Color.WHITE : Color.rgb(255, 225, 225));
            name.setTextSize(item.searchResult ? 8 : 9);
            name.setTypeface(Typeface.MONOSPACE, item.directory ? Typeface.BOLD : Typeface.NORMAL);
            name.setSingleLine(true);
            name.setEllipsize(item.searchResult ? TextUtils.TruncateAt.END : TextUtils.TruncateAt.MIDDLE);
            name.setContentDescription(item.path);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, dp(ROW_HEIGHT_DP), 1f);
            nameParams.setMargins(dp(2), 0, 0, 0);
            row.addView(name, nameParams);
            return row;
        }

        private String iconForFile(@NonNull String fileName) {
            String name = fileName.toLowerCase(Locale.ROOT);
            if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".log")) return "T";
            if (name.endsWith(".sh") || name.endsWith(".bash")) return "$";
            if (name.endsWith(".json") || name.endsWith(".conf") || name.endsWith(".xml") || name.endsWith(".yaml") || name.endsWith(".yml")) return "{}";
            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".gif") || name.endsWith(".webp")) return "▧";
            if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg")) return "♫";
            if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) return "▶";
            if (name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") || name.endsWith(".7z")) return "▤";
            if (name.endsWith(".apk")) return "A";
            return "•";
        }
    }
}