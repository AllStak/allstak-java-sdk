package dev.allstak.grpc;

import dev.allstak.scope.Scopes;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * gRPC server interceptor — copies inbound AllStak trace headers into
 * the active isolation scope so any error captured during the unary
 * handler inherits the caller's trace.
 */
public final class AllStakGrpcServerInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String traceId = headers.get(AllStakGrpcClientInterceptor.KEY_TRACE_ID);
        if (traceId != null) Scopes.isolation().setTag("trace.id", traceId);
        return next.startCall(call, headers);
    }
}
