package io.kestra.plugin.alibaba.oss;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

final class OssRegexp {
    static final int MAX_LENGTH = 256;
    private static final long TIMEOUT_NANOS = 500_000_000L;

    private OssRegexp() {
    }

    static Pattern compile(String regexp) {
        if (regexp.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("`regexp` must not be longer than " + MAX_LENGTH + " characters, got " + regexp.length());
        }

        try {
            return Pattern.compile(regexp);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("`regexp` is not a valid regular expression: " + e.getDescription(), e);
        }
    }

    static boolean matches(Pattern pattern, String key) {
        return matches(pattern, key, TIMEOUT_NANOS);
    }

    static boolean matches(Pattern pattern, String key, long timeoutNanos) {
        return pattern.matcher(new Bounded(key, System.nanoTime() + timeoutNanos)).matches();
    }

    private record Bounded(CharSequence delegate, long deadline) implements CharSequence {
        @Override
        public int length() {
            return delegate.length();
        }

        @Override
        public char charAt(int index) {
            if (System.nanoTime() > deadline) {
                throw new IllegalArgumentException("`regexp` took too long to evaluate, simplify it to avoid excessive backtracking");
            }
            return delegate.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Bounded(delegate.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
