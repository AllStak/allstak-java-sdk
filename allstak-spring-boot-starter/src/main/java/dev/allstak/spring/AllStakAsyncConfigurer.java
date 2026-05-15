package dev.allstak.spring;

import dev.allstak.AllStakClient;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.scheduling.annotation.AsyncConfigurer;

/**
 * Supplies AllStak's async exception handler when the application has not
 * provided its own {@link AsyncConfigurer}. This captures void-returning
 * {@code @Async} failures without blocking application threads.
 */
public final class AllStakAsyncConfigurer implements AsyncConfigurer {

    private final AllStakClient client;

    public AllStakAsyncConfigurer(AllStakClient client) {
        this.client = client;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AllStakAsyncExceptionHandler(client);
    }
}
