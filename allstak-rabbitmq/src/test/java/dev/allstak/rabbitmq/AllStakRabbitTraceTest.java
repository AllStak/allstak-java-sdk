package dev.allstak.rabbitmq;

import org.junit.jupiter.api.Test;

class AllStakRabbitTraceTest {
    @Test
    void nullPassthrough() {
        // No SDK init ⇒ no headers added.
        AllStakRabbitTrace.withHeaders(null);
        AllStakRabbitTrace.harvest(null);
    }
}
