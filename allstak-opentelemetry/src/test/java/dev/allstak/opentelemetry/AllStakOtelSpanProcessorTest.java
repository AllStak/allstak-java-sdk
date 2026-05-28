package dev.allstak.opentelemetry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakOtelSpanProcessorTest {
    @Test
    void capabilities() {
        AllStakOtelSpanProcessor sp = new AllStakOtelSpanProcessor();
        assertThat(sp.isStartRequired()).isFalse();
        assertThat(sp.isEndRequired()).isTrue();
    }
}
