package dev.allstak.spring;

import dev.allstak.AllStakClient;
import feign.Capability;
import feign.Client;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;

public final class AllStakFeignCapability implements Capability {
    private final AllStakClient client;

    public AllStakFeignCapability(AllStakClient client) {
        this.client = client;
    }

    @Override
    public Client enrich(Client delegate) {
        try {
            ProxyFactory proxyFactory = new ProxyFactory(delegate);
            proxyFactory.setProxyTargetClass(true);
            AllStakFeignClientPostProcessor postProcessor = new AllStakFeignClientPostProcessor(client);
            Object wrapped = postProcessor.postProcessAfterInitialization(delegate, "feignCapabilityClient");
            if (wrapped instanceof Client client) return client;
            MethodInterceptor noop = invocation -> invocation.proceed();
            proxyFactory.addAdvice(noop);
            return (Client) proxyFactory.getProxy();
        } catch (Exception ignored) {
            return delegate;
        }
    }
}
