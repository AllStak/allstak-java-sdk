package dev.allstak.grpc;

import dev.allstak.AllStak;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;

import java.util.UUID;

/**
 * gRPC client interceptor — injects AllStak trace headers as gRPC
 * metadata on every outbound call. Pair with
 * {@link AllStakGrpcServerInterceptor} on the receiving service so both
 * halves of the call observe the same trace id.
 */
public final class AllStakGrpcClientInterceptor implements ClientInterceptor {

    static final Metadata.Key<String> KEY_TRACE_ID =
            Metadata.Key.of("x-allstak-trace-id", Metadata.ASCII_STRING_MARSHALLER);
    static final Metadata.Key<String> KEY_SPAN_ID  =
            Metadata.Key.of("x-allstak-span-id", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions options, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, options)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                if (AllStak.getClient() != null) {
                    headers.put(KEY_TRACE_ID, UUID.randomUUID().toString());
                    headers.put(KEY_SPAN_ID,  UUID.randomUUID().toString().substring(0, 16));
                }
                super.start(responseListener, headers);
            }
        };
    }
}
