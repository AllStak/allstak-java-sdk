package dev.allstak.grpc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakGrpcInterceptorsTest {
    @Test
    void instantiates() {
        assertThat(new AllStakGrpcClientInterceptor()).isNotNull();
        assertThat(new AllStakGrpcServerInterceptor()).isNotNull();
    }
}
