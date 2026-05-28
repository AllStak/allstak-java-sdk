package dev.allstak.profiling_jfr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakJfrProfilerTest {

    @Test
    void startThenStop_noCrash() {
        AllStakJfrProfiler p = AllStakJfrProfiler.start();
        // start() may return null on JVMs without JFR (unlikely on JDK 17+).
        if (p != null) {
            p.stop();
        }
        assertThat(true).isTrue();
    }
}
