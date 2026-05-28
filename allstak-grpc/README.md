# allstak-grpc

gRPC `ClientInterceptor` + `ServerInterceptor` propagating AllStak
trace headers as gRPC metadata.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-grpc</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
// client side
ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
    .intercept(new AllStakGrpcClientInterceptor())
    .build();

// server side
Server server = ServerBuilder.forPort(port)
    .addService(myService)
    .intercept(new AllStakGrpcServerInterceptor())
    .build();
```

Metadata keys: `x-allstak-trace-id`, `x-allstak-span-id`.
