package dev.allstak.debug;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DebugIdResolverTest {

    @Test
    void returnsNullWhenResourceMissing() {
        // Not generated in test runtime by default.
        assertThat(DebugIdResolver.resolve()).isNull();
    }
}
