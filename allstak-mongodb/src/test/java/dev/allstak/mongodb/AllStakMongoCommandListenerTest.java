package dev.allstak.mongodb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakMongoCommandListenerTest {
    @Test
    void instantiates() {
        assertThat(new AllStakMongoCommandListener()).isNotNull();
    }
}
