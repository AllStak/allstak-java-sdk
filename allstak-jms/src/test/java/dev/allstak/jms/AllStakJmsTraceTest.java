package dev.allstak.jms;

import org.junit.jupiter.api.Test;

class AllStakJmsTraceTest {
    @Test
    void nullMessage_noOp() {
        AllStakJmsTrace.stamp(null);
        AllStakJmsTrace.harvest(null);
    }
}
