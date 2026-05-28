package dev.allstak.quartz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakQuartzJobListenerTest {

    @Test
    void hasStableName() {
        assertThat(new AllStakQuartzJobListener().getName()).isEqualTo("AllStakQuartzJobListener");
    }
}
