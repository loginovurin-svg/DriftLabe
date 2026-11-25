package com.termux.app.music;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.annotation.NonNull;

import com.termux.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local-audio player hosted in LogBox's right-side media panel.
 * Audio is read through MediaStore; no broad file-system access is needed.
 */
public final class LocalMusicPlayerView extends LinearLayout {
    public static final int REQUEST_READ_AUDIO = 8703;

    private static final int COLOR_ACCENT = Color.rgb(0, 240, 255);
    private static final int COLOR_AMBER = Color.rgb(255, 170, 0);
    private static final int COLOR_TEXT = Color.rgb(224, 244, 255);
    private static final int COLOR_MUTED = Color.rgb(100, 139, 166);
    private static final int COLOR_PANEL = Color.rgb(6, 17, 33);
    private static final int REPEAT_OFF = 0;
    private static final int REPEAT_ALL = 1;
    private static final int REPEAT_ONE = 2;

    private final Activity activity;
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AudioManager audioManager;
    private final AudioAttributes audioAttributes;
    private final Random random = new Random();
    private final AudioManager.OnAudioFocusChangeListener focusChangeListener =
        this::onAudioFocusChanged;

    private final List<Track> tracks = new ArrayList<>();
    private final ImageView coverArt;
    private final TextView currentTitle;
    private final TextView currentArtist;
    private final TextView currentGenre;
    private final TextView status;
    private final SeekBar seekBar;
    private final TextView elapsedTime;
    private final TextView durationTime;
    private final ImageButton playButton;
    private final ImageButton shuffleButton;
    private final ImageButton repeatButton;
    private final EqualizerView equalizerView;

    private AudioFocusRequest audioFocusRequest;
    private MediaPlayer player;
    private int currentIndex = -1;
    private int scanGeneration;
    private int repeatMode = REPEAT_OFF;
    private boolean prepared;
    private boolean isSeeking;
    private boolean destroyed;
    private boolean shuffleEnabled;
    private boolean showPickerAfterScan;

    private final Runnable updateProgress = new Runnable() {
        @Override
        public void run() {
            if (destroyed || player == null || !prepared) return;
            if (!isSeeking) {
                int position = player.getCurrentPosition();
                seekBar.setProgress(position);
                elapsedTime.setText(formatTime(position));
            }
            updatePlayButton();
            mainHandler.postDelayed(this, 500);
        }
    };

    public LocalMusicPlayerView(Activity activity) {
        super(activity);
        this.activity = activity;
        audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(6), dp(6), dp(6), dp(6));
        setBackground(makePanelBackground());
        setClipToOutline(true);
        setElevation(dp(3));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(HORIZONTAL);
        ImageView noteIcon = new ImageView(activity);
        noteIcon.setImageResource(R.drawable.ic_music_player_note);
        noteIcon.setColorFilter(COLOR_ACCENT);
        header.addView(noteIcon, new LayoutParams(dp(18), dp(18)));

        TextView heading = new TextView(activity);
        heading.setText(R.string.music_player_heading);
        heading.setTextColor(COLOR_TEXT);
        heading.setTextSize(13);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setSingleLine(true);
        LayoutParams headingParams = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1);
        headingParams.leftMargin = dp(7);
        header.addView(heading, headingParams);

        ImageButton menuButton = makeIconButton(R.drawable.ic_music_player_more,
            R.string.music_player_menu);
        menuButton.setOnClickListener(this::showPlayerMenu);
        header.addView(menuButton, new LayoutParams(dp(28), dp(26)));
        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, dp(24)));

        coverArt = new ImageView(activity);
        coverArt.setImageResource(R.drawable.music_player_cover);
        coverArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        coverArt.setBackground(makeArtworkBackground());
        coverArt.setClipToOutline(true);
        coverArt.setContentDescription(getString(R.string.music_player_art_description));
        LayoutParams coverParams = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1);
        coverParams.topMargin = dp(4);
        coverParams.bottomMargin = dp(5);
        addView(coverArt, coverParams);

        currentTitle = new TextView(activity);
        currentTitle.setText(R.string.music_player_preview_title);
        currentTitle.setTextColor(COLOR_TEXT);
        currentTitle.setTextSize(14);
        currentTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        currentTitle.setSingleLine(true);
        currentTitle.setEllipsize(TextUtils.TruncateAt.END);
        currentTitle.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LayoutParams titleParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(18));
        addView(currentTitle, titleParams);

        currentArtist = new TextView(activity);
        currentArtist.setText(R.string.music_player_preview_artist);
        currentArtist.setTextColor(COLOR_MUTED);
        currentArtist.setTextSize(11);
        currentArtist.setSingleLine(true);
        currentArtist.setEllipsize(TextUtils.TruncateAt.END);
        currentArtist.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        addView(currentArtist, new LayoutParams(LayoutParams.MATCH_PARENT, dp(14)));

        currentGenre = makeSmallLabel(getString(R.string.music_player_genre));
        currentGenre.setTextColor(0xff00b4d8);
        currentGenre.setTextSize(10);
        currentGenre.setSingleLine(true);
        addView(currentGenre, new LayoutParams(LayoutParams.MATCH_PARENT, dp(12)));

        status = makeSmallLabel(getString(R.string.music_player_choose_track_hint));
        status.setTextSize(9);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setVisibility(GONE);
        addView(status, new LayoutParams(LayoutParams.MATCH_PARENT, dp(14)));

        equalizerView = new EqualizerView(activity);
        LayoutParams visualizerParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(27));
        visualizerParams.topMargin = dp(1);
        visualizerParams.bottomMargin = dp(1);
        addView(equalizerView, visualizerParams);

        seekBar = new SeekBar(activity);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setProgressBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0x5500b4d8));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && prepared) elapsedTime.setText(formatTime(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                isSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (player != null && prepared) player.seekTo(bar.getProgress());
                isSeeking = false;
            }
        });
        seekBar.setPadding(0, 0, 0, 0);
        LayoutParams seekParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(17));
        addView(seekBar, seekParams);

        LinearLayout timeRow = new LinearLayout(activity);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        elapsedTime = makeSmallLabel("0:00");
        durationTime = makeSmallLabel("0:00");
        elapsedTime.setTextSize(9);
        durationTime.setTextSize(9);
        timeRow.addView(elapsedTime, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        durationTime.setGravity(Gravity.END);
        timeRow.addView(durationTime, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1));
        addView(timeRow, new LayoutParams(LayoutParams.MATCH_PARENT, dp(12)));

        LinearLayout controls = new LinearLayout(activity);
        controls.setGravity(Gravity.CENTER);
        controls.setOrientation(HORIZONTAL);

        shuffleButton = makeIconButton(R.drawable.ic_music_player_shuffle,
            R.string.music_player_shuffle);
        shuffleButton.setOnClickListener(view -> {
            shuffleEnabled = !shuffleEnabled;
            updateControlModes();
        });
        controls.addView(shuffleButton, new LayoutParams(0, dp(40), 0.8f));

        ImageButton previousButton = makeIconButton(android.R.drawable.ic_media_previous,
            R.string.music_player_previous);
        previousButton.setOnClickListener(view -> playPrevious());
        controls.addView(previousButton, new LayoutParams(0, dp(40), 0.9f));

        playButton = makeIconButton(android.R.drawable.ic_media_play, R.string.music_player_play);
        playButton.setBackground(makePlayButtonBackground());
        playButton.setOnClickListener(view -> togglePlayback());
        controls.addView(playButton, new LayoutParams(0, dp(40), 1.4f));

        ImageButton nextButton = makeIconButton(android.R.drawable.ic_media_next,
            R.string.music_player_next);
        nextButton.setOnClickListener(view -> playNext());
        controls.addView(nextButton, new LayoutParams(0, dp(40), 0.9f));

        repeatButton = makeIconButton(R.drawable.ic_music_player_repeat,
            R.string.music_player_repeat);
        repeatButton.setOnClickListener(view -> {
            repeatMode = (repeatMode + 1) % 3;
            updateControlModes();
        });
        controls.addView(repeatButton, new LayoutParams(0, dp(40), 0.8f));
        addView(controls, new LayoutParams(LayoutParams.MATCH_PARENT, dp(40)));

        LinearLayout volumeRow = new LinearLayout(activity);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView volumeIcon = new ImageView(activity);
        volumeIcon.setImageResource(R.drawable.ic_music_player_volume);
        volumeIcon.setColorFilter(COLOR_MUTED);
        volumeIcon.setContentDescription(getString(R.string.music_player_volume));
        volumeRow.addView(volumeIcon, new LayoutParams(dp(18), dp(18)));

        SeekBar volumeBar = new SeekBar(activity);
        int maxVolume = audioManager == null ? 15
            : Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        volumeBar.setMax(maxVolume);
        volumeBar.setProgressTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        volumeBar.setProgressBackgroundTintList(
            android.content.res.ColorStateList.valueOf(0x5500b4d8));
        volumeBar.setThumbTintList(android.content.res.ColorStateList.valueOf(COLOR_ACCENT));
        volumeBar.setPadding(0, 0, 0, 0);
        int currentVolume = audioManager == null ? maxVolume
            : audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        volumeBar.setProgress(currentVolume);
        LayoutParams volumeBarParams = new LayoutParams(0, dp(22), 1);
        volumeBarParams.leftMargin = dp(6);
        volumeBarParams.rightMargin = dp(5);
        volumeRow.addView(volumeBar, volumeBarParams);

        TextView volumeValue = makeSmallLabel(volumePercent(currentVolume, maxVolume));
        volumeValue.setTextSize(9);
        volumeValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        volumeRow.addView(volumeValue, new LayoutParams(dp(30), LayoutParams.MATCH_PARENT));
        volumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (audioManager != null) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                }
                volumeValue.setText(volumePercent(progress, maxVolume));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) { }

            @Override
            public void onStopTrackingTouch(SeekBar bar) { }
        });
        LayoutParams volumeParams = new LayoutParams(LayoutParams.MATCH_PARENT, dp(21));
        volumeParams.topMargin = dp(1);
        addView(volumeRow, volumeParams);

        updateControlModes();
        if (hasAudioPermission()) {
            scanLibrary();
        }
    }

    private void showPlayerMenu(View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.music_player_choose_track);
        menu.getMenu().add(0, 2, 1, R.string.music_player_refresh);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showTrackPicker();
                return true;
            }
            if (item.getItemId() == 2) {
                requestLibraryAccess();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showTrackPicker() {
        if (!hasAudioPermission()) {
            showPickerAfterScan = true;
            requestLibraryAccess();
            return;
        }
        if (tracks.isEmpty()) {
            showPickerAfterScan = true;
            scanLibrary();
            return;
        }
        showTrackPickerDialog();
    }

    private void showTrackPickerDialog() {
        if (tracks.isEmpty() || destroyed) return;
        String[] labels = new String[tracks.size()];
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            labels[i] = track.title + "  —  " + track.artist;
        }
        new AlertDialog.Builder(activity)
            .setTitle(R.string.music_player_choose_track)
            .setItems(labels, (dialog, which) -> playTrack(which))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void requestLibraryAccess() {
        if (hasAudioPermission()) {
            scanLibrary();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showStatus(R.string.music_player_permission_required);
            ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_AUDIO);
        } else {
            scanLibrary();
        }
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != REQUEST_READ_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scanLibrary();
        } else {
            showPickerAfterScan = false;
            showStatus(R.string.music_player_permission_denied);
        }
    }

    private void scanLibrary() {
        final int generation = ++scanGeneration;
        showStatus(R.string.music_player_scanning);

        scanExecutor.execute(() -> {
            List<Track> result = new ArrayList<>();
            String error = null;
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.DURATION
            };
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 AND "
                + MediaStore.Audio.Media.DURATION + " > 0";
            String sortOrder = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC";

            try (Cursor cursor = activity.getContentResolver().query(
                collection, projection, selection, null, sortOrder)) {
                if (cursor != null) {
                    int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idColumn);
                        String title = cursor.getString(titleColumn);
                        String artist = cursor.getString(artistColumn);
                        long duration = cursor.getLong(durationColumn);
                        Uri trackUri = ContentUris.withAppendedId(collection, id);
                        result.add(new Track(trackUri,
                            title == null || title.trim().isEmpty()
                                ? getString(R.string.music_player_unknown_title) : title,
                            artist == null || artist.trim().isEmpty()
                                ? getString(R.string.music_player_unknown_artist) : artist,
                            duration));
                    }
                }
            } catch (Exception exception) {
                error = exception.getMessage();
            }

            final String scanError = error;
            mainHandler.post(() -> {
                if (destroyed || generation != scanGeneration) return;
                tracks.clear();
                tracks.addAll(result);
                if (scanError != null) {
                    showStatus(R.string.music_player_scan_failed);
                } else if (tracks.isEmpty()) {
                    showStatus(R.string.music_player_no_tracks);
                } else {
                    status.setText(getResources().getQuantityString(
                        R.plurals.music_player_track_count, tracks.size(), tracks.size()));
                    status.setVisibility(GONE);
                    if (currentIndex >= tracks.size()) currentIndex = -1;
                }
                if (showPickerAfterScan) {
                    showPickerAfterScan = false;
                    if (!tracks.isEmpty()) showTrackPickerDialog();
                }
            });
        });
    }

    private void playTrack(int index) {
        if (index < 0 || index >= tracks.size()) return;
        currentIndex = index;
        Track track = tracks.get(index);
        currentTitle.setText(track.title);
        currentArtist.setText(track.artist);
        elapsedTime.setText("0:00");
        durationTime.setText(formatTime((int) track.duration));
        seekBar.setMax((int) Math.min(Integer.MAX_VALUE, track.duration));
        seekBar.setProgress(0);
        showStatus(R.string.music_player_loading_track);

        releasePlayer();
        if (!requestAudioFocus()) {
            showStatus(R.string.music_player_audio_focus_failed);
            updatePlayButton();
            return;
        }

        MediaPlayer nextPlayer = new MediaPlayer();
        player = nextPlayer;
        prepared = false;
        try {
            nextPlayer.setAudioAttributes(audioAttributes);
            nextPlayer.setDataSource(activity, track.uri);
            nextPlayer.setOnPreparedListener(mediaPlayer -> {
                if (destroyed || player != mediaPlayer) {
                    mediaPlayer.release();
                    return;
                }
                prepared = true;
                seekBar.setMax(mediaPlayer.getDuration());
                durationTime.setText(formatTime(mediaPlayer.getDuration()));
                mediaPlayer.start();
                hideStatus(R.string.music_player_playing);
                updatePlayButton();
                mainHandler.removeCallbacks(updateProgress);
                mainHandler.post(updateProgress);
            });
            nextPlayer.setOnCompletionListener(mediaPlayer -> handleTrackCompletion());
            nextPlayer.setOnErrorListener((mediaPlayer, what, extra) -> {
                if (player == mediaPlayer) {
                    releasePlayer();
                    showStatus(R.string.music_player_playback_failed);
                    updatePlayButton();
                }
                return true;
            });
            nextPlayer.prepareAsync();
        } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
            releasePlayer();
            showStatus(R.string.music_player_playback_failed);
            updatePlayButton();
        }
    }

    private void togglePlayback() {
        if (currentIndex < 0) {
            if (!tracks.isEmpty()) playTrack(0);
            else showTrackPicker();
            return;
        }
        if (player == null || !prepared) {
            playTrack(currentIndex);
            return;
        }
        if (player.isPlaying()) {
            player.pause();
            hideStatus(R.string.music_player_paused);
        } else if (requestAudioFocus()) {
            player.start();
            hideStatus(R.string.music_player_playing);
        }
        updatePlayButton();
    }

    private void playNext() {
        if (tracks.isEmpty()) return;
        if (shuffleEnabled && tracks.size() > 1) {
            int nextIndex = currentIndex;
            while (nextIndex == currentIndex) nextIndex = random.nextInt(tracks.size());
            playTrack(nextIndex);
        } else {
            playTrack((currentIndex + 1 + tracks.size()) % tracks.size());
        }
    }

    private void handleTrackCompletion() {
        if (tracks.isEmpty() || currentIndex < 0) return;
        if (repeatMode == REPEAT_ONE) {
            playTrack(currentIndex);
        } else if (shuffleEnabled) {
            playNext();
        } else if (currentIndex < tracks.size() - 1 || repeatMode == REPEAT_ALL) {
            playTrack((currentIndex + 1) % tracks.size());
        } else {
            showStatus(R.string.music_player_finished);
            releasePlayer();
        }
    }

    private void playPrevious() {
        if (tracks.isEmpty()) return;
        if (player != null && prepared && player.getCurrentPosition() > 3000) {
            player.seekTo(0);
            return;
        }
        int index = currentIndex <= 0 ? tracks.size() - 1 : currentIndex - 1;
        playTrack(index);
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioFocusRequest == null) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                    .setWillPauseWhenDucked(true)
                    .build();
            }
            return audioManager.requestAudioFocus(audioFocusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audioManager.requestAudioFocus(focusChangeListener, AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void onAudioFocusChanged(int focusChange) {
        if (player == null || !prepared) return;
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            player.setVolume(0.25f, 0.25f);
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
            player.setVolume(1f, 1f);
        } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS
            || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            if (player.isPlaying()) player.pause();
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS) abandonAudioFocus();
            updatePlayButton();
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        } else {
            audioManager.abandonAudioFocus(focusChangeListener);
        }
    }

    private void releasePlayer() {
        mainHandler.removeCallbacks(updateProgress);
        prepared = false;
        if (player != null) {
            MediaPlayer oldPlayer = player;
            player = null;
            oldPlayer.setOnPreparedListener(null);
            oldPlayer.setOnCompletionListener(null);
            oldPlayer.setOnErrorListener(null);
            oldPlayer.release();
        }
        abandonAudioFocus();
        updatePlayButton();
    }

    private void updatePlayButton() {
        if (playButton == null) return;
        boolean playing = player != null && prepared && player.isPlaying();
        playButton.setImageResource(playing
            ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        playButton.setColorFilter(COLOR_ACCENT);
        playButton.setContentDescription(getString(playing
            ? R.string.music_player_pause : R.string.music_player_play));
    }

    private void updateControlModes() {
        shuffleButton.setColorFilter(shuffleEnabled ? COLOR_ACCENT : COLOR_MUTED);
        shuffleButton.setContentDescription(getString(shuffleEnabled
            ? R.string.music_player_shuffle_on : R.string.music_player_shuffle));
        repeatButton.setColorFilter(repeatMode == REPEAT_OFF ? COLOR_MUTED : COLOR_ACCENT);
        int repeatDescription = repeatMode == REPEAT_ONE
            ? R.string.music_player_repeat_one
            : repeatMode == REPEAT_ALL
                ? R.string.music_player_repeat_all
                : R.string.music_player_repeat;
        repeatButton.setContentDescription(getString(repeatDescription));
    }

    private void showStatus(int stringId) {
        status.setText(stringId);
        status.setVisibility(VISIBLE);
    }

    private void hideStatus(int stringId) {
        status.setText(stringId);
        status.setVisibility(GONE);
    }

    public void shutdown() {
        destroyed = true;
        scanGeneration++;
        scanExecutor.shutdownNow();
        releasePlayer();
    }

    private ImageButton makeIconButton(int icon, int description) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(icon);
        button.setColorFilter(COLOR_ACCENT);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(getString(description));
        button.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(5), dp(5), dp(5), dp(5));
        return button;
    }

    private TextView makeSmallLabel(String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setTextColor(COLOR_MUTED);
        label.setTextSize(10);
        return label;
    }

    private GradientDrawable makePanelBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(0xe6061121);
        drawable.setCornerRadius(dp(11));
        drawable.setStroke(dp(1), 0x6600b4d8);
        return drawable;
    }

    private GradientDrawable makeArtworkBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(COLOR_PANEL);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), 0x5500b4d8);
        return drawable;
    }

    private GradientDrawable makePlayButtonBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(0x3300b4d8);
        drawable.setStroke(dp(1), COLOR_ACCENT);
        return drawable;
    }

    private String volumePercent(int value, int max) {
        return (int) Math.round(100.0 * value / Math.max(1, max)) + "%";
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int dp(int value) {
        return dp((float) value);
    }

    private String getString(int resourceId) {
        return activity.getString(resourceId);
    }

    private String formatTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private final class EqualizerView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bar = new RectF();
        private final int[] colors = {COLOR_ACCENT, 0xff00b4d8, 0xff24b8e8, COLOR_AMBER};
        private final float[] stops = {0f, 0.55f, 0.78f, 1f};

        EqualizerView(Context context) {
            super(context);
            setContentDescription(getString(R.string.music_player_visualizer));
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            if (width <= 0 || height <= 0) return;

            paint.setShader(new LinearGradient(0, 0, width, 0, colors, stops,
                Shader.TileMode.CLAMP));
            int count = 28;
            float gap = dp(3);
            float barWidth = Math.max(dp(2), (width - gap * (count - 1)) / count);
            float phase = SystemClock.uptimeMillis() / 420f;
            for (int i = 0; i < count; i++) {
                float envelope = 0.24f + 0.62f * (float) Math.abs(Math.sin(i * 0.39f + 0.7f));
                float pulse = 0.38f + 0.62f * (float) Math.abs(Math.sin(phase + i * 0.57f));
                float barHeight = Math.max(dp(3), height * envelope * pulse);
                float left = i * (barWidth + gap);
                float top = height - barHeight;
                bar.set(left, top, left + barWidth, height);
                canvas.drawRoundRect(bar, dp(2), dp(2), paint);
            }
            paint.setShader(null);
            if (!destroyed && isShown()) postInvalidateDelayed(95);
        }
    }

    private static final class Track {
        final Uri uri;
        final String title;
        final String artist;
        final long duration;

        Track(Uri uri, String title, String artist, long duration) {
            this.uri = uri;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
        }
    }
}
