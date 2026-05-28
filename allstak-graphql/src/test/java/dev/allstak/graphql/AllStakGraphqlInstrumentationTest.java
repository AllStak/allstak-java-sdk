package dev.allstak.graphql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakGraphqlInstrumentationTest {
    @Test
    void instantiates() {
        assertThat(new AllStakGraphqlInstrumentation()).isNotNull();
    }
}
