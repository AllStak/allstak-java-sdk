package dev.allstak.spring;

import dev.allstak.AllStakClient;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AllStakSecurityEventListener implements ApplicationListener<ApplicationEvent> {
    private final AllStakClient client;

    public AllStakSecurityEventListener(AllStakClient client) {
        this.client = client;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        try {
            if (event instanceof AbstractAuthenticationFailureEvent authFailure) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("error.category", "auth");
                metadata.put("auth.failure_type", authFailure.getException().getClass().getSimpleName());
                metadata.put("auth.principal.present", authFailure.getAuthentication() != null && authFailure.getAuthentication().getPrincipal() != null);
                client.captureException(authFailure.getException(), "warning", metadata);
                return;
            }

            String className = event.getClass().getName();
            if (className.equals("org.springframework.security.authorization.event.AuthorizationDeniedEvent")) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("error.category", "authorization");
                metadata.put("auth.failure_type", "access_denied");
                metadata.put("auth.authorization_event", className);
                Throwable ex = extractThrowable(event);
                client.captureException(ex != null ? ex : new SecurityException("Spring Security access denied"), "warning", metadata);
            }
        } catch (Exception ignored) {
            // Security event capture must never affect authentication flow.
        }
    }

    private static Throwable extractThrowable(Object event) {
        try {
            Method method = event.getClass().getMethod("getException");
            Object value = method.invoke(event);
            return value instanceof Throwable t ? t : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
