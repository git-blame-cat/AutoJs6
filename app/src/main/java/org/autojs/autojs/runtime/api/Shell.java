package org.autojs.autojs.runtime.api;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.autojs.autojs.pio.UncheckedIOException;
import org.autojs.autojs.runtime.ScriptRuntime;
import org.autojs.autojs.runtime.exception.ScriptInterruptedException;

import java.io.IOException;
import java.util.ArrayList;

import jackpal.androidterm.ShellTermSession;
import jackpal.androidterm.emulatorview.TermSession;
import jackpal.androidterm.util.TermSettings;

/**
 * Created by Stardust on Apr 24, 2017.
 */
public class Shell extends AbstractShell {

    public interface Callback {

        void onOutput(String str);

        void onNewLine(String line);

        void onInitialized();

        void onInterrupted(InterruptedException e);
    }

    public static class SimpleCallback implements Callback {

        @Override
        public void onOutput(String str) {
            /* Empty body. */
        }

        @Override
        public void onNewLine(String str) {
            /* Empty body. */
        }

        @Override
        public void onInitialized() {
            /* Empty body. */
        }

        @Override
        public void onInterrupted(InterruptedException e) {
            /* Empty body. */
        }
    }

    private static final boolean DEBUG = true;
    private static final String TAG = "Shell";
    private static final int MAX_COMMAND_OUTPUT_LINES = 4096;
    private static final int MAX_COMMAND_OUTPUT_CHARACTERS = 1 << 20;
    private static final int MAX_PENDING_LINE_CHARACTERS = 1 << 20;
    private static final String COMMAND_OUTPUT_TRUNCATED = "[shell output truncated]";

    private volatile TermSession mTermSession;
    private final Object mInitLock = new Object();
    private final Object mExitLock = new Object();
    private final Object mCommandOutputLock = new Object();
    private volatile RuntimeException mInitException;
    private volatile boolean mInitialized = false;
    private volatile boolean mWaitingExit = false;
    private volatile String mCommandOutput = null;
    private volatile boolean mWaitingCommandOutput = false;
    private Callback mCallback;

    public Shell(Context context, boolean root) {
        super(context, root);
    }

    public Shell(Context context) {
        this(context, false);
    }

    public Shell(boolean root) {
        this(ScriptRuntime.getApplicationContext(), root);
    }

    public Shell() {
        this(false);
    }

    @Override
    protected void init(final String initialCommand) {
        Handler uiHandler = new Handler(mContext.getMainLooper());
        uiHandler.post(() -> {
            TermSettings settings = new TermSettings(mContext.getResources(), PreferenceManager.getDefaultSharedPreferences(mContext));
            try {
                mTermSession = new MyShellTermSession(settings, initialCommand);
                mTermSession.initializeEmulator(1024, 40);
            } catch (IOException e) {
                mInitException = new UncheckedIOException(e);
            }
        });
    }

    public void exec(String command) {
        ensureInitialized();
        mTermSession.write(command + "\n");
    }

    public String execAndWaitFor(String command) {
        synchronized (mCommandOutputLock) {
            ensureInitialized();
            mCommandOutput = null;
            ((MyShellTermSession) mTermSession).resetCommandOutput();
            mWaitingCommandOutput = true;
            mTermSession.write(command + "\n");
            try {
                while (mWaitingCommandOutput) {
                    mCommandOutputLock.wait();
                }
                return mCommandOutput;
            } catch (InterruptedException e) {
                mWaitingCommandOutput = false;
                ((MyShellTermSession) mTermSession).resetCommandOutput();
                throw new ScriptInterruptedException();
            }
        }
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public boolean isInitialized() {
        return mInitialized;
    }

    private void ensureInitialized() {
        if (mTermSession == null) {
            logDebug("ensureInitialized: not init");
            checkInitException();
            waitInitialization();
            if (mTermSession == null) {
                checkInitException();
                throw new IllegalStateException();
            }
        } else {
            logDebug("ensureInitialized: init");
        }
    }

    private void logDebug(String log) {
        if (DEBUG) {
            Log.d(TAG, log);
        }
    }

    private void checkInitException() {
        if (mInitException != null) {
            throw mInitException;
        }
    }

    private void waitInitialization() {
        synchronized (mInitLock) {
            if (mInitialized) {
                return;
            }
            logDebug("waitInitialization: enter");
            try {
                mInitLock.wait();
                logDebug("waitInitialization: exit");
            } catch (InterruptedException e) {
                onInterrupted(e);
            }
        }
    }

    private void onInterrupted(InterruptedException e) {
        if (mCallback == null) {
            exit();
            throw new ScriptInterruptedException();
        } else {
            mCallback.onInterrupted(e);
        }
    }

    @Override
    public void exit() {
        mTermSession.finish();
    }

    @Override
    public void exitAndWaitFor() {
        execExitAndWait();
        if (!isExecWithRoot()) {
            return;
        }
        execExitAndWait();
    }

    private void execExitAndWait() {
        synchronized (mExitLock) {
            mWaitingExit = true;
            exec("exit");
            try {
                mExitLock.wait();
            } catch (InterruptedException e) {
                onInterrupted(e);
            }
        }
    }

    public TermSession getTermSession() {
        return mTermSession;
    }

    private class MyShellTermSession extends ShellTermSession {

        private final BoundedTextAccumulator mPendingLine =
                new BoundedTextAccumulator(MAX_PENDING_LINE_CHARACTERS);
        private final ArrayList<String> mCommandOutputs = new ArrayList<>();
        private int mCommandOutputCharacterCount;
        private boolean mCommandOutputTruncated;

        public MyShellTermSession(TermSettings settings, String initialCommand) throws IOException {
            super(settings, initialCommand);
        }

        private void onNewLine(String line) {
            if (!line.isEmpty() && !line.contains("/dev/input/event")) {
                logDebug("onNewLine: " + line);
            }
            if (mInitialized) {
                appendCommandOutput(line);
            }
            if (mCallback != null) {
                mCallback.onNewLine(line);
            }
            if (mWaitingExit && line.endsWith(" exit")) {
                notifyExit();
            }
        }

        private void appendCommandOutput(String line) {
            synchronized (mCommandOutputLock) {
                if (!mWaitingCommandOutput || mCommandOutputTruncated) {
                    return;
                }
                if (mCommandOutputs.size() >= MAX_COMMAND_OUTPUT_LINES
                        || mCommandOutputCharacterCount + line.length() > MAX_COMMAND_OUTPUT_CHARACTERS) {
                    mCommandOutputs.add(COMMAND_OUTPUT_TRUNCATED);
                    mCommandOutputTruncated = true;
                    return;
                }
                mCommandOutputs.add(line);
                mCommandOutputCharacterCount += line.length();
            }
        }

        private void markCommandOutputTruncated() {
            synchronized (mCommandOutputLock) {
                if (!mWaitingCommandOutput || mCommandOutputTruncated) {
                    return;
                }
                // onCommandOutput skips the first line because it is the echoed command.
                if (mCommandOutputs.isEmpty()) {
                    mCommandOutputs.add("");
                }
                mCommandOutputs.add(COMMAND_OUTPUT_TRUNCATED);
                mCommandOutputTruncated = true;
            }
        }

        private void resetCommandOutput() {
            synchronized (mCommandOutputLock) {
                mCommandOutputs.clear();
                mCommandOutputCharacterCount = 0;
                mCommandOutputTruncated = false;
            }
        }

        private void onCommandOutput() {
            synchronized (mCommandOutputLock) {
                if (!mWaitingCommandOutput) {
                    return;
                }
                StringBuilder result = new StringBuilder();
                for (int i = 1; i < mCommandOutputs.size(); i++) {
                    result.append(mCommandOutputs.get(i));
                    if (i < mCommandOutputs.size() - 1) {
                        result.append("\n");
                    }
                }
                logDebug("onCommandOutput: lines=" + mCommandOutputs.size()
                        + ", chars=" + result.length()
                        + (mCommandOutputTruncated ? ", truncated" : ""));
                mCommandOutput = result.toString();
                mWaitingCommandOutput = false;
                resetCommandOutput();
                mCommandOutputLock.notifyAll();
            }
        }

        private void onOutput(String str) {
            if (!str.isEmpty() && !str.contains("/dev/input/event")) {
                logDebug("onOutput: " + str);
            }
            int start = 0;
            int i = str.indexOf("\n", start);
            while (i >= 0) {
                mPendingLine.append(str, start, i);
                BoundedTextAccumulator.Snapshot line = mPendingLine.consume();
                onNewLine(line.text.trim());
                if (line.truncated) {
                    markCommandOutputTruncated();
                }
                start = i + 1;
                i = str.indexOf("\n", start);
            }
            if (start < str.length()) {
                mPendingLine.append(str, start, str.length());
            }
            String prompt = str.endsWith(" # ") || mPendingLine.endsWith(" # ") ? " # "
                    : str.endsWith(" $ ") || mPendingLine.endsWith(" $ ") ? " $ " : null;
            if (prompt != null) {
                BoundedTextAccumulator.Snapshot tail = mPendingLine.consume();
                if (!mInitialized) {
                    boolean expectedPrompt = isExecWithRoot()
                            ? " # ".equals(prompt)
                            : " $ ".equals(prompt);
                    if (expectedPrompt) {
                        notifyInitialized();
                    }
                } else {
                    String outputWithoutPrompt = tail.text.endsWith(prompt)
                            ? tail.text.substring(0, tail.text.length() - prompt.length())
                            : tail.text;
                    if (mWaitingCommandOutput && !outputWithoutPrompt.isEmpty()) {
                        onNewLine(outputWithoutPrompt.trim());
                    }
                    if (mWaitingCommandOutput && tail.truncated) {
                        markCommandOutputTruncated();
                    }
                    onCommandOutput();
                }
            }
            if (mCallback != null) {
                mCallback.onOutput(str.replace("\r", ""));
            }
        }

        @Override
        protected void processInput(byte[] data, int offset, int count) {
            onOutput(new String(data, offset, count));
        }

        private void notifyExit() {
            synchronized (mExitLock) {
                mWaitingExit = false;
                mExitLock.notify();
            }
        }

        private void notifyInitialized() {
            logDebug("notifyInitialized");
            mInitialized = true;
            synchronized (mInitLock) {
                mInitLock.notifyAll();
            }
            if (mCallback != null) {
                mCallback.onInitialized();
            }
        }

        @Override
        protected void onProcessExit() {
            super.onProcessExit();
            synchronized (mExitLock) {
                mWaitingExit = false;
                mExitLock.notify();
            }
        }

    }

}
