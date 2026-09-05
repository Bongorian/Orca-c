package com.hundredrabbits.orcac;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String PREFS = "orca_c_state";
    private static final int TERM_BG = 0xff101214;
    private static final int TERM_FG = 0xffe8e8e8;
    private static final int TERM_DOT = 0xff51565c;
    private static final int TERM_DIM = 0xff7b828a;
    private static final int TERM_CYAN = 0xff00d7d7;
    private static final int TERM_YELLOW = 0xffd7d700;
    private static final int TERM_RED = 0xffff5f5f;
    private static final int TERM_BORDER = 0xffd8d8d8;
    private static final int TERM_SHADE = 0xff1b1f23;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final Map<Integer, Runnable> activeNoteOffs = new HashMap<>();
    private final List<MidiChoice> midiChoices = new ArrayList<>();

    private long session;
    private TerminalView terminalView;
    private MidiManager midiManager;
    private MidiDevice midiDevice;
    private MidiInputPort midiPort;
    private int selectedMidiChoice;

    private boolean playing;
    private int bpm = 120;
    private int seed = 1;
    private int tick;
    private boolean midiEnabled;
    private boolean oscEnabled;
    private boolean udpEnabled;
    private boolean vmDirty = true;
    private boolean keepScreenAwake = true;
    private boolean autoSaveEnabled = true;
    private String oscHost = "127.0.0.1";
    private String oscPort = "49162";
    private String fileName = "orca.orca";
    private String status = "";
    private String eventsText = "No events";

    private final Runnable autoSaveTask = new Runnable() {
        @Override
        public void run() {
            saveAutoState();
        }
    };

    private final MidiManager.DeviceCallback midiDeviceCallback = new MidiManager.DeviceCallback() {
        @Override
        public void onDeviceAdded(MidiDeviceInfo device) {
            refreshMidiDevices();
            setStatus("midi device added");
        }

        @Override
        public void onDeviceRemoved(MidiDeviceInfo device) {
            boolean removedCurrent = midiDevice != null && device != null
                    && midiDevice.getInfo().getId() == device.getId();
            refreshMidiDevices();
            if (removedCurrent) {
                closeMidi();
                selectedMidiChoice = 0;
                setStatus("midi device removed");
            } else {
                setStatus("midi devices");
            }
        }
    };

    private final Runnable playTick = new Runnable() {
        @Override
        public void run() {
            if (!playing) {
                return;
            }
            stepOnce();
            handler.postDelayed(this, Math.max(10L, Math.round(frameMillis())));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(TERM_BG);
        getWindow().setNavigationBarColor(TERM_BG);
        applyImmersiveMode();
        session = OrcaNative.create();
        midiManager = (MidiManager) getSystemService(MIDI_SERVICE);
        terminalView = new TerminalView(this);
        setContentView(terminalView);
        restoreState();
        handleExternalIntent(getIntent());
        applyKeepScreenAwake();
        refreshMidiDevices();
        if (midiManager != null) {
            midiManager.registerDeviceCallback(midiDeviceCallback, handler);
        }
        resetVmFromGrid();
        terminalView.requestFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (handleExternalIntent(intent)) {
            resetVmFromGrid();
            scheduleAutoSave();
        }
    }

    @Override
    protected void onPause() {
        saveAutoState();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    protected void onDestroy() {
        saveAutoState();
        handler.removeCallbacks(autoSaveTask);
        if (midiManager != null) {
            midiManager.unregisterDeviceCallback(midiDeviceCallback);
        }
        stopPlayback();
        closeMidi();
        cancelAllNotes();
        networkExecutor.shutdownNow();
        if (session != 0) {
            OrcaNative.destroy(session);
            session = 0;
        }
        super.onDestroy();
    }

    private void resetVmFromGrid() {
        OrcaNative.load(session, terminalView.gridToString(), seed);
        tick = 0;
        eventsText = "No events";
        vmDirty = false;
        setStatus("tick 0");
    }

    private void stepOnce() {
        if (vmDirty) {
            resetVmFromGrid();
        }
        String grid = OrcaNative.step(session, 1);
        String wire = OrcaNative.getEventsWire(session);
        dispatchEvents(wire);
        terminalView.loadGrid(grid);
        vmDirty = false;
        eventsText = OrcaNative.getEvents(session);
        tick = (int) OrcaNative.getTick(session);
        setStatus("tick " + tick);
    }

    private void togglePlayback() {
        if (playing) {
            stopPlayback();
            return;
        }
        playing = true;
        setStatus("playing tick " + tick);
        handler.post(playTick);
    }

    private void stopPlayback() {
        playing = false;
        handler.removeCallbacks(playTick);
        cancelAllNotes();
        setStatus("stopped");
    }

    private void setStatus(String value) {
        status = String.format(Locale.US, "ORCA  %s  %d BPM  seed %d", value, bpm, seed);
        if (terminalView != null) {
            terminalView.invalidate();
        }
    }

    private void applyImmersiveMode() {
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void applyKeepScreenAwake() {
        if (keepScreenAwake) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void scheduleAutoSave() {
        if (terminalView == null) {
            return;
        }
        if (!autoSaveEnabled) {
            saveSettingsState();
            return;
        }
        handler.removeCallbacks(autoSaveTask);
        handler.postDelayed(autoSaveTask, 750);
    }

    private void saveAutoState() {
        saveState(autoSaveEnabled);
    }

    private void saveSettingsState() {
        saveState(false);
    }

    private void saveSessionNow() {
        saveState(true);
    }

    private void saveState(boolean includeGrid) {
        if (terminalView == null) {
            return;
        }
        SharedPreferences.Editor editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        if (includeGrid) {
            editor.putString("grid", terminalView.gridToString());
        }
        editor.putString("fileName", fileName);
        editor.putString("oscHost", oscHost);
        editor.putString("oscPort", oscPort);
        editor.putInt("bpm", bpm);
        editor.putInt("seed", seed);
        editor.putInt("tick", tick);
        editor.putBoolean("midiEnabled", midiEnabled);
        editor.putBoolean("oscEnabled", oscEnabled);
        editor.putBoolean("udpEnabled", udpEnabled);
        editor.putBoolean("keepScreenAwake", keepScreenAwake);
        editor.putBoolean("autoSaveEnabled", autoSaveEnabled);
        editor.apply();
    }

    private void restoreState() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        bpm = prefs.getInt("bpm", bpm);
        seed = prefs.getInt("seed", seed);
        tick = prefs.getInt("tick", tick);
        midiEnabled = prefs.getBoolean("midiEnabled", midiEnabled);
        oscEnabled = prefs.getBoolean("oscEnabled", oscEnabled);
        udpEnabled = prefs.getBoolean("udpEnabled", udpEnabled);
        keepScreenAwake = prefs.getBoolean("keepScreenAwake", keepScreenAwake);
        autoSaveEnabled = prefs.getBoolean("autoSaveEnabled", autoSaveEnabled);
        fileName = prefs.getString("fileName", fileName);
        oscHost = prefs.getString("oscHost", oscHost);
        oscPort = prefs.getString("oscPort", oscPort);
        String grid = prefs.getString("grid", null);
        if (grid != null && !grid.isEmpty()) {
            terminalView.loadGrid(grid);
            setStatus("restored");
        }
    }

    private boolean handleExternalIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null && text.length() > 0) {
                loadExternalGrid(text.toString(), "shared text");
                return true;
            }
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                return loadExternalUri(stream);
            }
        }
        if (Intent.ACTION_VIEW.equals(action) || Intent.ACTION_EDIT.equals(action)) {
            Uri uri = intent.getData();
            if (uri != null) {
                return loadExternalUri(uri);
            }
        }
        return false;
    }

    private boolean loadExternalUri(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                setStatus("open failed");
                return false;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (;;) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            String name = uri.getLastPathSegment();
            if (name != null && !name.trim().isEmpty()) {
                fileName = sanitizeFileName(name);
            }
            loadExternalGrid(out.toString("UTF-8"), "opened");
            return true;
        } catch (IOException | SecurityException e) {
            setStatus("open failed");
            return false;
        }
    }

    private void loadExternalGrid(String grid, String source) {
        terminalView.loadGrid(grid);
        terminalView.closeMenu();
        hideKeyboard();
        vmDirty = true;
        tick = 0;
        eventsText = "No events";
        setStatus(source);
    }

    private void showKeyboardForEdit() {
        terminalView.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.restartInput(terminalView);
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 80);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && terminalView != null) {
            imm.hideSoftInputFromWindow(terminalView.getWindowToken(), 0);
        }
    }

    private void quitApplication() {
        saveAutoState();
        hideKeyboard();
        stopPlayback();
        finishAndRemoveTask();
        handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 250);
    }

    private void saveFile() {
        try (FileOutputStream out = openFileOutput(fileName, MODE_PRIVATE)) {
            out.write(terminalView.gridToString().getBytes(StandardCharsets.UTF_8));
            scheduleAutoSave();
            setStatus("saved " + fileName);
        } catch (IOException e) {
            setStatus("save failed");
        }
    }

    private void openFile() {
        try (FileInputStream in = openFileInput(fileName)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (;;) {
                int read = in.read(buffer);
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            terminalView.loadGrid(out.toString("UTF-8"));
            vmDirty = true;
            resetVmFromGrid();
            scheduleAutoSave();
            setStatus("opened " + fileName);
        } catch (IOException e) {
            setStatus("open failed");
        }
    }

    private String sanitizeFileName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            return "orca.orca";
        }
        return trimmed.replace('/', '_').replace('\\', '_');
    }

    private void dispatchEvents(String wire) {
        if (wire == null || wire.isEmpty()) {
            return;
        }
        for (String line : wire.split("\\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split(" ");
            try {
                switch (parts[0]) {
                    case "MN":
                        sendMidiNote(parts);
                        break;
                    case "MC":
                        sendMidi3(0xb0 | clamp(parse(parts[1]), 15), clamp(parse(parts[2]), 127), clamp(parse(parts[3]), 127));
                        break;
                    case "MP":
                        sendMidi3(0xe0 | clamp(parse(parts[1]), 15), clamp(parse(parts[2]), 127), clamp(parse(parts[3]), 127));
                        break;
                    case "OI":
                        sendOsc(parts);
                        break;
                    case "US":
                        sendUdp(parts);
                        break;
                    default:
                        break;
                }
            } catch (RuntimeException ignored) {
                setStatus("malformed event");
            }
        }
    }

    private void sendMidiNote(String[] parts) {
        int channel = clamp(parse(parts[1]), 15);
        int octave = clamp(parse(parts[2]), 10);
        int note = clamp(parse(parts[3]), 127);
        int velocity = clamp(parse(parts[4]), 127);
        int duration = clamp(parse(parts[5]), 127);
        boolean mono = parse(parts[6]) != 0;
        int noteNumber = Math.min(127, octave * 12 + note);
        if (mono) {
            noteOffChannel(channel);
        }
        noteOn(channel, noteNumber, velocity, Math.max(1L, Math.round(frameMillis() * duration)));
    }

    private void noteOn(int channel, int noteNumber, int velocity, long durationMs) {
        int key = (channel << 8) | noteNumber;
        Runnable old = activeNoteOffs.remove(key);
        if (old != null) {
            handler.removeCallbacks(old);
            sendMidi3(0x80 | channel, noteNumber, 0);
        }
        sendMidi3(0x90 | channel, noteNumber, velocity);
        Runnable noteOff = () -> {
            activeNoteOffs.remove(key);
            sendMidi3(0x80 | channel, noteNumber, 0);
        };
        activeNoteOffs.put(key, noteOff);
        handler.postDelayed(noteOff, durationMs);
    }

    private void noteOffChannel(int channel) {
        for (Integer key : new ArrayList<>(activeNoteOffs.keySet())) {
            if ((key >> 8) == channel) {
                Runnable noteOff = activeNoteOffs.remove(key);
                if (noteOff != null) {
                    handler.removeCallbacks(noteOff);
                    sendMidi3(0x80 | channel, key & 0x7f, 0);
                }
            }
        }
    }

    private void cancelAllNotes() {
        for (Integer key : new ArrayList<>(activeNoteOffs.keySet())) {
            Runnable noteOff = activeNoteOffs.remove(key);
            if (noteOff != null) {
                handler.removeCallbacks(noteOff);
                sendMidi3(0x80 | ((key >> 8) & 0xf), key & 0x7f, 0);
            }
        }
    }

    private void sendMidi3(int statusByte, int data1, int data2) {
        if (!midiEnabled || midiPort == null) {
            return;
        }
        byte[] msg = {(byte) statusByte, (byte) data1, (byte) data2};
        try {
            midiPort.send(msg, 0, msg.length);
        } catch (IOException e) {
            setStatus("midi send failed");
        }
    }

    private void sendOsc(String[] parts) {
        if (!oscEnabled || parts.length < 3) {
            return;
        }
        String path = "/" + parts[1];
        int count = Math.min(parse(parts[2]), parts.length - 3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeOscString(out, path);
        StringBuilder tags = new StringBuilder(",");
        for (int i = 0; i < count; ++i) {
            tags.append('i');
        }
        writeOscString(out, tags.toString());
        ByteBuffer intBuffer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < count; ++i) {
            intBuffer.clear();
            intBuffer.putInt(parse(parts[3 + i]));
            out.write(intBuffer.array(), 0, 4);
        }
        sendDatagram(out.toByteArray());
    }

    private void sendUdp(String[] parts) {
        if (!udpEnabled || parts.length < 2) {
            return;
        }
        int count = Math.min(parse(parts[1]), parts.length - 2);
        byte[] data = new byte[count];
        for (int i = 0; i < count; ++i) {
            data[i] = (byte) clamp(parse(parts[2 + i]), 255);
        }
        sendDatagram(data);
    }

    private void sendDatagram(byte[] data) {
        String host = oscHost.trim().isEmpty() ? "127.0.0.1" : oscHost.trim();
        String portText = oscPort.trim().isEmpty() ? "49162" : oscPort.trim();
        networkExecutor.execute(() -> {
            try (DatagramSocket socket = new DatagramSocket()) {
                InetAddress address = InetAddress.getByName(host);
                int port = Integer.parseInt(portText);
                socket.send(new DatagramPacket(data, data.length, address, port));
            } catch (IOException | NumberFormatException e) {
                handler.post(() -> setStatus("udp/osc failed"));
            }
        });
    }

    private void writeOscString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
        out.write(0);
        int pad = (4 - ((bytes.length + 1) % 4)) % 4;
        for (int i = 0; i < pad; ++i) {
            out.write(0);
        }
    }

    private void refreshMidiDevices() {
        midiChoices.clear();
        midiChoices.add(new MidiChoice("No MIDI Output", null, -1));
        if (midiManager != null) {
            for (MidiDeviceInfo info : midiManager.getDevices()) {
                for (MidiDeviceInfo.PortInfo port : info.getPorts()) {
                    if (port.getType() == MidiDeviceInfo.PortInfo.TYPE_INPUT) {
                        midiChoices.add(new MidiChoice(deviceName(info, port), info, port.getPortNumber()));
                    }
                }
            }
        }
        if (selectedMidiChoice >= midiChoices.size()) {
            selectedMidiChoice = 0;
        }
    }

    private String deviceName(MidiDeviceInfo info, MidiDeviceInfo.PortInfo port) {
        String name = info.getProperties().getString(MidiDeviceInfo.PROPERTY_NAME);
        if (name == null || name.isEmpty()) {
            name = "Device " + info.getId();
        }
        String portName = port.getName();
        if (portName == null || portName.isEmpty()) {
            portName = "Port " + port.getPortNumber();
        }
        return name + " - " + portName;
    }

    private void selectMidi(int index) {
        closeMidi();
        selectedMidiChoice = Math.max(0, Math.min(index, midiChoices.size() - 1));
        if (selectedMidiChoice == 0 || midiManager == null) {
            setStatus("midi off");
            return;
        }
        MidiChoice choice = midiChoices.get(selectedMidiChoice);
        midiManager.openDevice(choice.info, device -> {
            if (device == null) {
                setStatus("midi open failed");
                return;
            }
            midiDevice = device;
            midiPort = device.openInputPort(choice.portNumber);
            setStatus(midiPort == null ? "midi port failed" : "midi ready");
        }, handler);
    }

    private void closeMidi() {
        try {
            if (midiPort != null) {
                midiPort.close();
            }
            if (midiDevice != null) {
                midiDevice.close();
            }
        } catch (IOException ignored) {
        }
        midiPort = null;
        midiDevice = null;
    }

    private double frameMillis() {
        return 60000.0 / Math.max(1, bpm) / 4.0;
    }

    private static int parse(String value) {
        return Integer.parseInt(value);
    }

    private static int parseOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(max, value));
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private enum MenuId {
        MAIN,
        GRID,
        MIDI,
        OSC,
        CLOCK,
        ANDROID
    }

    private enum EditTarget {
        NONE,
        BPM,
        SEED,
        WIDTH,
        HEIGHT,
        OSC_HOST,
        OSC_PORT,
        FILE_NAME
    }

    private final class TerminalView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint();
        private char[][] grid;
        private int rows = 18;
        private int cols = 32;
        private int cursorY = 0;
        private int cursorX = 0;
        private int scrollY = 0;
        private int scrollX = 0;
        private int cursorH = 1;
        private int cursorW = 1;
        private boolean appendMode;
        private boolean menuOpen;
        private MenuId menuId = MenuId.MAIN;
        private int menuIndex;
        private EditTarget editTarget = EditTarget.NONE;
        private String editBuffer = "";
        private float charW;
        private float charH;
        private float ascent;
        private float downX;
        private float downY;
        private int downScrollX;
        private int downScrollY;
        private boolean dragging;
        private boolean expandInitialGrid = true;

        TerminalView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            setBackgroundColor(TERM_BG);
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setSubpixelText(true);
            updateTextMetrics();
            initGrid(rows, cols);
            loadExample();
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return editTarget != EditTarget.NONE;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            if (editTarget == EditTarget.NONE) {
                outAttrs.inputType = InputType.TYPE_NULL;
                return null;
            }
            outAttrs.inputType = editInputType();
            outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    | EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
            outAttrs.initialSelStart = editBuffer.length();
            outAttrs.initialSelEnd = editBuffer.length();
            return new BaseInputConnection(this, false) {
                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    for (int i = 0; i < text.length(); ++i) {
                        char c = text.charAt(i);
                        if (c == '\n' || c == '\r') {
                            commitEdit();
                        } else if (c >= 32 && c <= 126) {
                            editBuffer += c;
                        }
                    }
                    invalidate();
                    return true;
                }

                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    if (!editBuffer.isEmpty()) {
                        editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
                        invalidate();
                    }
                    return true;
                }

                @Override
                public boolean performEditorAction(int editorAction) {
                    commitEdit();
                    return true;
                }
            };
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            requestFocus();
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                downScrollX = scrollX;
                downScrollY = scrollY;
                dragging = false;
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8)) {
                    dragging = true;
                    scrollX = clamp(downScrollX - Math.round(dx / Math.max(1f, charW)), maxScrollX());
                    scrollY = clamp(downScrollY - Math.round(dy / Math.max(1f, charH)), maxScrollY());
                    invalidate();
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = event.getX() - downX;
                float dy = Math.abs(event.getY() - downY);
                if (dy < dp(96) && Math.abs(dx) > dp(72)) {
                    if (dx < 0 && downX > getWidth() - dp(48)) {
                        openMenu(MenuId.MAIN);
                        return true;
                    }
                    if (dx > 0 && menuOpen) {
                        closeMenu();
                        return true;
                    }
                }
                if (dragging) {
                    return true;
                }
                int y = (int) ((event.getY() - dp(8)) / charH) + scrollY;
                int x = (int) ((event.getX() - dp(8)) / charW) + scrollX;
                if (y >= 0 && y < rows && x >= 0 && x < cols) {
                    cursorY = y;
                    cursorX = x;
                    ensureCursorVisible();
                    invalidate();
                }
                return true;
            }
            return true;
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (editTarget != EditTarget.NONE) {
                return handleEditKey(keyCode, event);
            }
            if (menuOpen) {
                return handleMenuKey(keyCode, event);
            }
            boolean ctrl = event.isCtrlPressed();
            boolean shift = event.isShiftPressed();
            boolean alt = event.isAltPressed();
            if (keyCode == KeyEvent.KEYCODE_F1 || ctrl && keyCode == KeyEvent.KEYCODE_D) {
                openMenu(MenuId.MAIN);
                return true;
            }
            if (ctrl && keyCode == KeyEvent.KEYCODE_SPACE) {
                togglePlayback();
                return true;
            }
            if (ctrl && keyCode == KeyEvent.KEYCODE_F) {
                stepOnce();
                return true;
            }
            if (ctrl && keyCode == KeyEvent.KEYCODE_R) {
                tick = 0;
                resetVmFromGrid();
                return true;
            }
            if (ctrl && keyCode == KeyEvent.KEYCODE_I || keyCode == KeyEvent.KEYCODE_INSERT) {
                appendMode = !appendMode;
                setStatus(appendMode ? "append" : "overwrite");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                cursorH = 1;
                cursorW = 1;
                invalidate();
                return true;
            }
            if (handleGridSizeKey(keyCode, event)) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                backspace();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
                put('.');
                return true;
            }
            if (handleArrow(keyCode, shift, alt)) {
                return true;
            }
            int unicode = event.getUnicodeChar();
            if (unicode >= 32 && unicode <= 126) {
                insertChar((char) unicode);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            updateTextMetrics();
            canvas.drawColor(TERM_BG);
            drawGrid(canvas);
            drawStatus(canvas);
            if (menuOpen) {
                drawMenu(canvas);
            }
            if (editTarget != EditTarget.NONE) {
                drawEditBox(canvas);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            updateTextMetrics();
            if (expandInitialGrid && w > 0 && h > 0) {
                int targetRows = Math.max(rows, visibleRows());
                if (targetRows > rows) {
                    resizeGrid(targetRows, cols);
                }
                expandInitialGrid = false;
            }
        }

        String gridToString() {
            StringBuilder sb = new StringBuilder(rows * (cols + 1));
            for (int y = 0; y < rows; ++y) {
                sb.append(grid[y]);
                sb.append('\n');
            }
            return sb.toString();
        }

        void loadGrid(String source) {
            String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            int newRows = 0;
            int newCols = 1;
            for (String line : lines) {
                if (line.isEmpty() && newRows == lines.length - 1) {
                    continue;
                }
                newRows++;
                newCols = Math.max(newCols, line.length());
            }
            newRows = Math.max(1, newRows);
            initGrid(newRows, newCols);
            for (int y = 0; y < newRows && y < lines.length; ++y) {
                String line = lines[y];
                for (int x = 0; x < Math.min(cols, line.length()); ++x) {
                    grid[y][x] = normalize(line.charAt(x));
                }
            }
            cursorY = Math.min(cursorY, rows - 1);
            cursorX = Math.min(cursorX, cols - 1);
            expandGridToVisibleArea();
            ensureCursorVisible();
            invalidate();
        }

        private void loadExample() {
            String[] lines = {
                    "................................",
                    ".1C............................",
                    "................................",
                    ".1:03Cff4......................",
                    "................................",
                    ".1=a123........................",
                    ".1;hello......................."
            };
            for (int y = 0; y < Math.min(rows, lines.length); ++y) {
                for (int x = 0; x < Math.min(cols, lines[y].length()); ++x) {
                    grid[y][x] = normalize(lines[y].charAt(x));
                }
            }
        }

        private void initGrid(int newRows, int newCols) {
            rows = Math.max(1, Math.min(256, newRows));
            cols = Math.max(1, Math.min(256, newCols));
            grid = new char[rows][cols];
            for (int y = 0; y < rows; ++y) {
                for (int x = 0; x < cols; ++x) {
                    grid[y][x] = '.';
                }
            }
        }

        private void resizeGrid(int newRows, int newCols) {
            newRows = Math.max(1, Math.min(256, newRows));
            newCols = Math.max(1, Math.min(256, newCols));
            char[][] old = grid;
            int oldRows = rows;
            int oldCols = cols;
            initGrid(newRows, newCols);
            for (int y = 0; y < Math.min(oldRows, rows); ++y) {
                System.arraycopy(old[y], 0, grid[y], 0, Math.min(oldCols, cols));
            }
            cursorY = Math.min(cursorY, rows - 1);
            cursorX = Math.min(cursorX, cols - 1);
            vmDirty = true;
            tick = 0;
            eventsText = "No events";
            scheduleAutoSave();
            setStatus("edited");
            invalidate();
        }

        private void expandGridToVisibleArea() {
            if (getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            int targetRows = Math.max(rows, visibleRows());
            int targetCols = Math.max(cols, visibleCols());
            if (targetRows <= rows && targetCols <= cols) {
                return;
            }
            char[][] old = grid;
            int oldRows = rows;
            int oldCols = cols;
            initGrid(targetRows, targetCols);
            for (int y = 0; y < Math.min(oldRows, rows); ++y) {
                System.arraycopy(old[y], 0, grid[y], 0, Math.min(oldCols, cols));
            }
            cursorY = Math.min(cursorY, rows - 1);
            cursorX = Math.min(cursorX, cols - 1);
        }

        private void drawGrid(Canvas canvas) {
            int top = dp(8);
            int left = dp(8);
            int visibleRows = visibleRows();
            int visibleCols = visibleCols();
            scrollY = clamp(scrollY, maxScrollY());
            scrollX = clamp(scrollX, maxScrollX());
            int endY = Math.min(rows, scrollY + visibleRows);
            int endX = Math.min(cols, scrollX + visibleCols);
            fill.setStyle(Paint.Style.FILL);
            for (int y = scrollY; y < endY; ++y) {
                for (int x = scrollX; x < endX; ++x) {
                    char c = grid[y][x];
                    int sx = left + Math.round((x - scrollX) * charW);
                    int sy = top + Math.round((y - scrollY) * charH);
                    int fg = TERM_FG;
                    int bg = TERM_BG;
                    boolean bold = false;
                    if (c == '.') {
                        fg = TERM_DOT;
                        bold = true;
                    } else if (c == '#') {
                        fg = TERM_DIM;
                    } else if (c >= 'A' && c <= 'Z') {
                        bg = TERM_CYAN;
                    } else if (c == '*') {
                        bg = TERM_YELLOW;
                        bold = true;
                    } else {
                        bold = true;
                    }
                    boolean cursor = y >= cursorY && y < cursorY + cursorH && x >= cursorX && x < cursorX + cursorW;
                    if (cursor) {
                        bg = playing ? TERM_YELLOW : TERM_FG;
                        fg = playing ? TERM_FG : TERM_BG;
                    }
                    if (bg != TERM_BG) {
                        fill.setColor(bg);
                        canvas.drawRect(sx, sy, sx + charW, sy + charH, fill);
                    }
                    paint.setColor(fg);
                    paint.setFakeBoldText(bold);
                    canvas.drawText(String.valueOf(c), sx, sy + ascent, paint);
                }
            }
            paint.setFakeBoldText(false);
        }

        private void drawStatus(Canvas canvas) {
            float top = getHeight() - statusHeight();
            float textY = top + dp(22);
            fill.setColor(TERM_SHADE);
            canvas.drawRect(0, top, getWidth(), getHeight(), fill);
            paint.setColor(TERM_FG);
            paint.setFakeBoldText(false);
            paint.setTextSize(dp(11));
            String mode = appendMode ? "APPEND" : "OVERWRITE";
            String line = status + "  " + mode + "  " + rows + "x" + cols;
            String firstEvent = eventsText == null ? "" : eventsText.split("\\n", 2)[0];
            if (!firstEvent.isEmpty() && !"No events".equals(firstEvent)) {
                line += "  " + firstEvent;
            }
            canvas.drawText(fitLine(line, getWidth() - dp(12)), dp(6), textY, paint);
            updateTextMetrics();
        }

        private void drawMenu(Canvas canvas) {
            String[] items = menuItems();
            float oldTextSize = paint.getTextSize();
            paint.setTextSize(Math.min(oldTextSize, dp(22)));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float menuAscent = -fm.ascent;
            float menuLineH = (float) Math.ceil(fm.descent - fm.ascent + dp(4));
            float neededW = paint.measureText(menuTitle()) + dp(40);
            for (String item : items) {
                neededW = Math.max(neededW, paint.measureText(item) + dp(28));
            }
            float w = Math.min(getWidth() - dp(24), Math.max(dp(330), neededW));
            float h = dp(44) + items.length * menuLineH;
            float x = dp(14);
            float y = dp(14);
            drawBox(canvas, x, y, w, h, menuTitle());
            for (int i = 0; i < items.length; ++i) {
                float rowY = y + dp(30) + i * menuLineH;
                if (i == menuIndex) {
                    fill.setColor(TERM_FG);
                    canvas.drawRect(x + dp(8), rowY - dp(1), x + w - dp(8), rowY + menuLineH - dp(1), fill);
                    paint.setColor(TERM_BG);
                } else {
                    paint.setColor(TERM_FG);
                }
                canvas.drawText(items[i], x + dp(14), rowY + menuAscent, paint);
            }
            paint.setTextSize(oldTextSize);
        }

        private void drawEditBox(Canvas canvas) {
            float oldTextSize = paint.getTextSize();
            paint.setTextSize(Math.min(oldTextSize, dp(22)));
            Paint.FontMetrics fm = paint.getFontMetrics();
            float menuAscent = -fm.ascent;
            float w = Math.min(getWidth() - dp(48), dp(360));
            float h = dp(72);
            float x = (getWidth() - w) / 2f;
            float y = (getHeight() - h) / 2f;
            drawBox(canvas, x, y, w, h, editTitle());
            paint.setColor(TERM_FG);
            canvas.drawText(editBuffer, x + dp(14), y + dp(42) + menuAscent, paint);
            paint.setTextSize(oldTextSize);
        }

        private void drawBox(Canvas canvas, float x, float y, float w, float h, String title) {
            fill.setColor(TERM_BG);
            canvas.drawRect(x, y, x + w, y + h, fill);
            fill.setColor(TERM_BORDER);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(dp(1));
            canvas.drawRect(new RectF(x, y, x + w, y + h), fill);
            fill.setStyle(Paint.Style.FILL);
            paint.setColor(TERM_FG);
            paint.setFakeBoldText(false);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(" " + title + " ", x + dp(10), y - fm.ascent, paint);
        }

        private boolean handleArrow(int keyCode, boolean shift, boolean alt) {
            int dy = 0;
            int dx = 0;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                dy = -1;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                dy = 1;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                dx = -1;
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                dx = 1;
            } else {
                return false;
            }
            if (alt) {
                slideSelection(dy, dx);
            } else if (shift) {
                cursorH = Math.max(1, cursorH + dy);
                cursorW = Math.max(1, cursorW + dx);
            } else {
                cursorY = clamp(cursorY + dy, rows - 1);
                cursorX = clamp(cursorX + dx, cols - 1);
                cursorH = 1;
                cursorW = 1;
            }
            ensureCursorVisible();
            invalidate();
            return true;
        }

        private boolean handleGridSizeKey(int keyCode, KeyEvent event) {
            int unicode = event.getUnicodeChar();
            switch (unicode) {
                case '(':
                    resizeGrid(rows, cols - 1);
                    return true;
                case ')':
                    resizeGrid(rows, cols + 1);
                    return true;
                case '_':
                    resizeGrid(rows - 1, cols);
                    return true;
                case '+':
                    resizeGrid(rows + 1, cols);
                    return true;
                case '[':
                    resizeGrid(rows, Math.max(1, cols - 8));
                    return true;
                case ']':
                    resizeGrid(rows, cols + 8);
                    return true;
                case '{':
                    resizeGrid(Math.max(1, rows - 8), cols);
                    return true;
                case '}':
                    resizeGrid(rows + 8, cols);
                    return true;
                case '<':
                    bpm = Math.max(1, bpm - 1);
                    scheduleAutoSave();
                    setStatus("bpm");
                    return true;
                case '>':
                    bpm++;
                    scheduleAutoSave();
                    setStatus("bpm");
                    return true;
                default:
                    return false;
            }
        }

        private boolean handleMenuKey(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                closeMenu();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                menuIndex = Math.max(0, menuIndex - 1);
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                menuIndex = Math.min(menuItems().length - 1, menuIndex + 1);
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                adjustMenuItem(-1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                adjustMenuItem(1);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                activateMenuItem();
                return true;
            }
            int unicode = event.getUnicodeChar();
            if (unicode == '<') {
                bpm = Math.max(1, bpm - 1);
                scheduleAutoSave();
                setStatus("bpm");
                return true;
            }
            if (unicode == '>') {
                bpm++;
                scheduleAutoSave();
                setStatus("bpm");
                return true;
            }
            return true;
        }

        private boolean handleEditKey(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                editTarget = EditTarget.NONE;
                hideKeyboard();
                invalidate();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                commitEdit();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                if (!editBuffer.isEmpty()) {
                    editBuffer = editBuffer.substring(0, editBuffer.length() - 1);
                    invalidate();
                }
                return true;
            }
            int unicode = event.getUnicodeChar();
            if (unicode >= 32 && unicode <= 126) {
                editBuffer += (char) unicode;
                invalidate();
            }
            return true;
        }

        private void activateMenuItem() {
            if (menuId == MenuId.MAIN) {
                switch (menuIndex) {
                    case 0:
                        initGrid(18, 32);
                        cursorY = cursorX = 0;
                        vmDirty = true;
                        tick = 0;
                        eventsText = "No events";
                        scheduleAutoSave();
                        setStatus("new");
                        closeMenu();
                        return;
                    case 1:
                        openFile();
                        return;
                    case 2:
                        saveFile();
                        return;
                    case 3:
                        beginEdit(EditTarget.FILE_NAME, fileName);
                        return;
                    case 5:
                        beginEdit(EditTarget.BPM, String.valueOf(bpm));
                        return;
                    case 6:
                        openMenu(MenuId.GRID);
                        return;
                    case 7:
                        resizeGrid(Math.max(rows, usedRows()), Math.max(cols, longestRow()));
                        return;
                    case 9:
                        openMenu(MenuId.OSC);
                        return;
                    case 10:
                        openMenu(MenuId.MIDI);
                        return;
                    case 12:
                        openMenu(MenuId.CLOCK);
                        return;
                    case 13:
                        openMenu(MenuId.ANDROID);
                        return;
                    case 14:
                        quitApplication();
                        return;
                    default:
                        return;
                }
            }
            if (menuId == MenuId.GRID) {
                switch (menuIndex) {
                    case 0:
                        beginEdit(EditTarget.WIDTH, String.valueOf(cols));
                        return;
                    case 1:
                        beginEdit(EditTarget.HEIGHT, String.valueOf(rows));
                        return;
                    case 2:
                        resizeGrid(rows, Math.max(cols, longestRow()));
                        return;
                    default:
                        openMenu(MenuId.MAIN);
                        return;
                }
            }
            if (menuId == MenuId.MIDI) {
                switch (menuIndex) {
                    case 0:
                        midiEnabled = !midiEnabled;
                        setStatus("midi " + (midiEnabled ? "on" : "off"));
                        scheduleAutoSave();
                        invalidate();
                        return;
                    case 1:
                        refreshMidiDevices();
                        selectMidi((selectedMidiChoice + 1) % Math.max(1, midiChoices.size()));
                        scheduleAutoSave();
                        invalidate();
                        return;
                    default:
                        openMenu(MenuId.MAIN);
                        return;
                }
            }
            if (menuId == MenuId.OSC) {
                switch (menuIndex) {
                    case 0:
                        oscEnabled = !oscEnabled;
                        setStatus("osc " + (oscEnabled ? "on" : "off"));
                        scheduleAutoSave();
                        invalidate();
                        return;
                    case 1:
                        udpEnabled = !udpEnabled;
                        setStatus("udp " + (udpEnabled ? "on" : "off"));
                        scheduleAutoSave();
                        invalidate();
                        return;
                    case 2:
                        beginEdit(EditTarget.OSC_HOST, oscHost);
                        return;
                    case 3:
                        beginEdit(EditTarget.OSC_PORT, oscPort);
                        return;
                    default:
                        openMenu(MenuId.MAIN);
                        return;
                }
            }
            if (menuId == MenuId.CLOCK) {
                switch (menuIndex) {
                    case 0:
                        beginEdit(EditTarget.BPM, String.valueOf(bpm));
                        return;
                    case 1:
                        beginEdit(EditTarget.SEED, String.valueOf(seed));
                        return;
                    default:
                        openMenu(MenuId.MAIN);
                        return;
                }
            }
            if (menuId == MenuId.ANDROID) {
                switch (menuIndex) {
                    case 0:
                        keepScreenAwake = !keepScreenAwake;
                        applyKeepScreenAwake();
                        saveSettingsState();
                        setStatus("awake " + (keepScreenAwake ? "on" : "off"));
                        invalidate();
                        return;
                    case 1:
                        autoSaveEnabled = !autoSaveEnabled;
                        saveSettingsState();
                        setStatus("autosave " + (autoSaveEnabled ? "on" : "off"));
                        invalidate();
                        return;
                    case 2:
                        saveSessionNow();
                        setStatus("session saved");
                        invalidate();
                        return;
                    default:
                        openMenu(MenuId.MAIN);
                        return;
                }
            }
        }

        private void adjustMenuItem(int delta) {
            if (menuId == MenuId.CLOCK && menuIndex == 0 || menuId == MenuId.MAIN && menuIndex == 5) {
                bpm = Math.max(1, bpm + delta);
                scheduleAutoSave();
                setStatus("bpm");
            } else if (menuId == MenuId.CLOCK && menuIndex == 1) {
                seed = Math.max(0, seed + delta);
                resetVmFromGrid();
                scheduleAutoSave();
            } else if (menuId == MenuId.GRID && menuIndex == 0) {
                resizeGrid(rows, cols + delta);
            } else if (menuId == MenuId.GRID && menuIndex == 1) {
                resizeGrid(rows + delta, cols);
            } else if (menuId == MenuId.MIDI && menuIndex == 1 && !midiChoices.isEmpty()) {
                selectMidi((selectedMidiChoice + delta + midiChoices.size()) % midiChoices.size());
                scheduleAutoSave();
            }
            invalidate();
        }

        private int editInputType() {
            switch (editTarget) {
                case BPM:
                case SEED:
                case WIDTH:
                case HEIGHT:
                case OSC_PORT:
                    return InputType.TYPE_CLASS_NUMBER;
                case OSC_HOST:
                case FILE_NAME:
                    return InputType.TYPE_CLASS_TEXT
                            | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
                case NONE:
                default:
                    return InputType.TYPE_NULL;
            }
        }

        private void beginEdit(EditTarget target, String value) {
            editTarget = target;
            editBuffer = value;
            requestFocus();
            showKeyboardForEdit();
            invalidate();
        }

        private void commitEdit() {
            switch (editTarget) {
                case BPM:
                    bpm = Math.max(1, parseOrDefault(editBuffer, bpm));
                    break;
                case SEED:
                    seed = Math.max(0, parseOrDefault(editBuffer, seed));
                    resetVmFromGrid();
                    break;
                case WIDTH:
                    resizeGrid(rows, parseOrDefault(editBuffer, cols));
                    break;
                case HEIGHT:
                    resizeGrid(parseOrDefault(editBuffer, rows), cols);
                    break;
                case OSC_HOST:
                    oscHost = editBuffer.trim().isEmpty() ? "127.0.0.1" : editBuffer.trim();
                    break;
                case OSC_PORT:
                    oscPort = editBuffer.trim().isEmpty() ? "49162" : editBuffer.trim();
                    break;
                case FILE_NAME:
                    fileName = sanitizeFileName(editBuffer);
                    saveFile();
                    break;
                default:
                    break;
            }
            editTarget = EditTarget.NONE;
            hideKeyboard();
            scheduleAutoSave();
            setStatus("updated");
            invalidate();
        }

        private String[] menuItems() {
            switch (menuId) {
                case GRID:
                    return new String[]{
                            "Set Grid Width... " + cols,
                            "Set Grid Height... " + rows,
                            "Auto-fit Grid",
                            "Back"
                    };
                case MIDI:
                    return new String[]{
                            "[" + (midiEnabled ? "*" : " ") + "] MIDI Output",
                            "> " + midiChoices.get(Math.max(0, Math.min(selectedMidiChoice, midiChoices.size() - 1))).label,
                            "Back"
                    };
                case OSC:
                    return new String[]{
                            "[" + (oscEnabled ? "*" : " ") + "] OSC Output",
                            "[" + (udpEnabled ? "*" : " ") + "] UDP Output",
                            "Address... " + oscHost,
                            "Port... " + oscPort,
                            "Back"
                    };
                case CLOCK:
                    return new String[]{
                            "BPM... " + bpm,
                            "Seed... " + seed,
                            "Back"
                    };
                case ANDROID:
                    return new String[]{
                            "[" + (keepScreenAwake ? "*" : " ") + "] Keep Screen Awake",
                            "[" + (autoSaveEnabled ? "*" : " ") + "] Auto-save Session",
                            "Save Session Now",
                            "Back"
                    };
                case MAIN:
                default:
                    return new String[]{
                            "New",
                            "Open... " + fileName,
                            "Save",
                            "Save As...",
                            "",
                            "Set BPM... " + bpm,
                            "Set Grid Size...",
                            "Auto-fit Grid",
                            "",
                            "OSC Output...",
                            "MIDI Output...",
                            "",
                            "Clock & Timing...",
                            "Android...",
                            "Close"
                    };
            }
        }

        private String menuTitle() {
            switch (menuId) {
                case GRID:
                    return "Grid";
                case MIDI:
                    return "MIDI Output";
                case OSC:
                    return "OSC Output";
                case CLOCK:
                    return "Clock & Timing";
                case ANDROID:
                    return "Android";
                case MAIN:
                default:
                    return "ORCA";
            }
        }

        private String editTitle() {
            switch (editTarget) {
                case BPM:
                    return "Set BPM";
                case SEED:
                    return "Set Seed";
                case WIDTH:
                    return "Set Grid Width";
                case HEIGHT:
                    return "Set Grid Height";
                case OSC_HOST:
                    return "OSC Address";
                case OSC_PORT:
                    return "OSC Port";
                case FILE_NAME:
                    return "Save As";
                default:
                    return "";
            }
        }

        private void openMenu(MenuId id) {
            refreshMidiDevices();
            menuOpen = true;
            menuId = id;
            menuIndex = 0;
            editTarget = EditTarget.NONE;
            hideKeyboard();
            invalidate();
        }

        private void closeMenu() {
            menuOpen = false;
            editTarget = EditTarget.NONE;
            hideKeyboard();
            invalidate();
        }

        private void insertChar(char c) {
            if (c == '\n' || c == '\r') {
                stepOnce();
                return;
            }
            if (c < 32 || c > 126) {
                return;
            }
            if (appendMode) {
                slideRowRight(cursorY, cursorX);
            }
            put(normalize(c));
            cursorX = Math.min(cols - 1, cursorX + 1);
            ensureCursorVisible();
            vmDirty = true;
            tick = 0;
            eventsText = "No events";
            scheduleAutoSave();
            setStatus("edited");
            invalidate();
        }

        private void commitTypedChar(char c) {
            if (editTarget != EditTarget.NONE) {
                if (c == '\n' || c == '\r') {
                    commitEdit();
                } else if (c >= 32 && c <= 126) {
                    editBuffer += c;
                    invalidate();
                }
                return;
            }
            insertChar(c);
        }

        private void backspace() {
            if (cursorX > 0) {
                cursorX--;
            }
            put('.');
            vmDirty = true;
            tick = 0;
            eventsText = "No events";
            scheduleAutoSave();
            setStatus("edited");
            invalidate();
        }

        private void put(char c) {
            for (int y = cursorY; y < Math.min(rows, cursorY + cursorH); ++y) {
                for (int x = cursorX; x < Math.min(cols, cursorX + cursorW); ++x) {
                    grid[y][x] = c;
                }
            }
        }

        private void slideSelection(int dy, int dx) {
            int y2 = Math.max(0, Math.min(rows - cursorH, cursorY + dy));
            int x2 = Math.max(0, Math.min(cols - cursorW, cursorX + dx));
            if (y2 == cursorY && x2 == cursorX) {
                return;
            }
            char[][] copy = new char[cursorH][cursorW];
            for (int y = 0; y < cursorH; ++y) {
                for (int x = 0; x < cursorW; ++x) {
                    copy[y][x] = grid[cursorY + y][cursorX + x];
                    grid[cursorY + y][cursorX + x] = '.';
                }
            }
            cursorY = y2;
            cursorX = x2;
            for (int y = 0; y < cursorH; ++y) {
                for (int x = 0; x < cursorW; ++x) {
                    grid[cursorY + y][cursorX + x] = copy[y][x];
                }
            }
            vmDirty = true;
            tick = 0;
            eventsText = "No events";
            scheduleAutoSave();
            setStatus("edited");
        }

        private void slideRowRight(int y, int fromX) {
            for (int x = cols - 1; x > fromX; --x) {
                grid[y][x] = grid[y][x - 1];
            }
        }

        private int longestRow() {
            int longest = 1;
            for (int y = 0; y < rows; ++y) {
                int last = 0;
                for (int x = 0; x < cols; ++x) {
                    if (grid[y][x] != '.') {
                        last = x + 1;
                    }
                }
                longest = Math.max(longest, last);
            }
            return longest;
        }

        private int usedRows() {
            int used = 1;
            for (int y = 0; y < rows; ++y) {
                for (int x = 0; x < cols; ++x) {
                    if (grid[y][x] != '.') {
                        used = y + 1;
                    }
                }
            }
            return used;
        }

        private void ensureCursorVisible() {
            int visibleRows = visibleRows();
            int visibleCols = visibleCols();
            if (cursorY < scrollY) {
                scrollY = cursorY;
            } else if (cursorY >= scrollY + visibleRows) {
                scrollY = cursorY - visibleRows + 1;
            }
            if (cursorX < scrollX) {
                scrollX = cursorX;
            } else if (cursorX >= scrollX + visibleCols) {
                scrollX = cursorX - visibleCols + 1;
            }
            scrollY = clamp(scrollY, maxScrollY());
            scrollX = clamp(scrollX, maxScrollX());
        }

        private char normalize(char c) {
            return c == ' ' ? '.' : c;
        }

        private String fitLine(String line, float maxWidth) {
            if (paint.measureText(line) <= maxWidth) {
                return line;
            }
            String suffix = "...";
            int end = line.length();
            while (end > 0 && paint.measureText(line, 0, end) + paint.measureText(suffix) > maxWidth) {
                end--;
            }
            return line.substring(0, Math.max(0, end)) + suffix;
        }

        private void updateTextMetrics() {
            float min = dp(8);
            float max = dp(28);
            float target = max;
            float availableW = Math.max(dp(32), getWidth() - dp(16));
            float availableH = Math.max(dp(80), getHeight() - statusHeight() - dp(16));
            for (float size = max; size >= min; size -= 0.5f) {
                paint.setTextSize(size);
                Paint.FontMetrics fm = paint.getFontMetrics();
                float h = (float) Math.ceil(fm.descent - fm.ascent + Math.max(1f, size * 0.12f));
                float w = paint.measureText("M");
                if (w * cols <= availableW && h * rows <= availableH) {
                    target = size;
                    break;
                }
                target = size;
            }
            paint.setTextSize(target);
            Paint.FontMetrics fm = paint.getFontMetrics();
            charH = (float) Math.ceil(fm.descent - fm.ascent + Math.max(1f, target * 0.12f));
            ascent = -fm.ascent;
            charW = paint.measureText("M");
        }

        private int visibleRows() {
            return Math.max(1, (int) ((getHeight() - statusHeight() - dp(16)) / Math.max(1f, charH)));
        }

        private int visibleCols() {
            return Math.max(1, (int) ((getWidth() - dp(16)) / Math.max(1f, charW)));
        }

        private int maxScrollY() {
            return Math.max(0, rows - visibleRows());
        }

        private int maxScrollX() {
            return Math.max(0, cols - visibleCols());
        }

        private int statusHeight() {
            return dp(46);
        }
    }

    private static final class MidiChoice {
        final String label;
        final MidiDeviceInfo info;
        final int portNumber;

        MidiChoice(String label, MidiDeviceInfo info, int portNumber) {
            this.label = label;
            this.info = info;
            this.portNumber = portNumber;
        }
    }
}
