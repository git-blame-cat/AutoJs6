package org.autojs.autojs.runtime.api;

final class BoundedTextAccumulator {

    private static final int MAX_SUFFIX_CHARACTERS = 64;

    private final int mMaxCharacters;
    private final StringBuilder mText = new StringBuilder();
    private final StringBuilder mSuffix = new StringBuilder();
    private boolean mTruncated;

    BoundedTextAccumulator(int maxCharacters) {
        if (maxCharacters < 0) {
            throw new IllegalArgumentException("maxCharacters must not be negative");
        }
        mMaxCharacters = maxCharacters;
    }

    void append(CharSequence text, int start, int end) {
        if (start < 0 || end < start || end > text.length()) {
            throw new IndexOutOfBoundsException("invalid range: " + start + ".." + end);
        }
        int requested = end - start;
        int remaining = mMaxCharacters - mText.length();
        int accepted = Math.min(requested, Math.max(remaining, 0));
        if (accepted > 0) {
            mText.append(text, start, start + accepted);
        }
        if (accepted < requested) {
            mTruncated = true;
        }
        appendSuffix(text, start, end);
    }

    boolean isTruncated() {
        return mTruncated;
    }

    boolean endsWith(CharSequence suffix) {
        int suffixLength = suffix.length();
        if (suffixLength > mSuffix.length()) {
            return false;
        }
        int offset = mSuffix.length() - suffixLength;
        for (int i = 0; i < suffixLength; i++) {
            if (mSuffix.charAt(offset + i) != suffix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private void appendSuffix(CharSequence text, int start, int end) {
        int requested = end - start;
        if (requested >= MAX_SUFFIX_CHARACTERS) {
            mSuffix.setLength(0);
            mSuffix.append(text, end - MAX_SUFFIX_CHARACTERS, end);
            return;
        }
        int overflow = mSuffix.length() + requested - MAX_SUFFIX_CHARACTERS;
        if (overflow > 0) {
            mSuffix.delete(0, overflow);
        }
        mSuffix.append(text, start, end);
    }

    Snapshot consume() {
        Snapshot snapshot = new Snapshot(mText.toString(), mTruncated);
        reset();
        return snapshot;
    }

    void reset() {
        mText.setLength(0);
        mSuffix.setLength(0);
        mTruncated = false;
    }

    static final class Snapshot {
        final String text;
        final boolean truncated;

        private Snapshot(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
