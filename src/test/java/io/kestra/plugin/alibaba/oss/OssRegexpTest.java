package io.kestra.plugin.alibaba.oss;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OssRegexpTest {
    @Test
    void matchesTheWholeKey() {
        var pattern = OssRegexp.compile(".*\\.csv");

        assertThat(OssRegexp.matches(pattern, "landing/a.csv"), is(true));
        assertThat(OssRegexp.matches(pattern, "landing/a.csv.bak"), is(false));
    }

    @Test
    void stopsEvaluatingWhenTheTimeoutIsExceeded() {
        var pattern = OssRegexp.compile("a+");

        var exception = assertThrows(IllegalArgumentException.class, () -> OssRegexp.matches(pattern, "aaaa", -1L));
        assertThat(exception.getMessage(), containsString("took too long"));
    }

    @Test
    void rejectsTooLongPatterns() {
        var exception = assertThrows(IllegalArgumentException.class, () -> OssRegexp.compile("a".repeat(OssRegexp.MAX_LENGTH + 1)));
        assertThat(exception.getMessage(), containsString("regexp"));
    }

    @Test
    void rejectsInvalidPatterns() {
        var exception = assertThrows(IllegalArgumentException.class, () -> OssRegexp.compile("(unclosed"));
        assertThat(exception.getMessage(), containsString("not a valid regular expression"));
    }
}
