package dev.allstak.r2dbc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakR2dbcProxyListenerTest {
    @Test
    void instantiates() {
        assertThat(new AllStakR2dbcProxyListener()).isNotNull();
    }
}
