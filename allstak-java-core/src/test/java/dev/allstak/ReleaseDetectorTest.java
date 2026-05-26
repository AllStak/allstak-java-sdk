package dev.allstak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ReleaseDetectorTest {

    @Test
    void parsesDescribeOutputFromRunner() {
        ReleaseDetector.GitRunner runner = args -> {
            assertThat(args).containsExactly("git", "describe", "--tags", "--always", "--dirty");
            return "v1.2.3-4-gabc1234-dirty\n";
        };
        assertThat(ReleaseDetector.parse(runner)).isEqualTo("v1.2.3-4-gabc1234-dirty");
    }

    @Test
    void runnerReturningNullFallsThrough() {
        ReleaseDetector.GitRunner runner = args -> null;
        assertThat(ReleaseDetector.parse(runner)).isNull();
    }

    @Test
    void runnerReturningBlankFallsThrough() {
        ReleaseDetector.GitRunner runner = args -> "   \n";
        assertThat(ReleaseDetector.parse(runner)).isNull();
    }

    @Test
    void runnerThrowingIsSwallowed() {
        ReleaseDetector.GitRunner runner = args -> { throw new RuntimeException("git not found"); };
        assertThat(ReleaseDetector.parse(runner)).isNull();
    }

    @Test
    void nullRunnerReturnsNull() {
        assertThat(ReleaseDetector.parse(null)).isNull();
    }
}
