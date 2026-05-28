package dev.allstak.spring_security;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.AllStakConfig;
import dev.allstak.scope.Scopes;
import dev.allstak.transport.HttpTransport;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AllStakSecurityUserEnricherTest {

    @AfterEach
    void tearDown() { AllStak.reset(); SecurityContextHolder.clearContext(); }

    private void init(boolean sendDefaultPii) {
        AllStakConfig cfg = AllStakConfig.builder()
                .apiKey("ask_live_test").environment("test").release("v0.0.1")
                .enableAutoSessionTracking(false).installUncaughtExceptionHandler(false)
                .sendDefaultPii(sendDefaultPii).build();
        AllStak.init(new AllStakClient(cfg, new HttpTransport("http://127.0.0.1:1", cfg.getApiKey())));
    }

    @Test
    void capturesPrincipalAndAuthorities() {
        init(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-99", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"))));

        AllStakSecurityUserEnricher.apply();

        assertThat(Scopes.current().getUser().getId()).isEqualTo("user-99");
        assertThat(Scopes.current().getUser().getEmail()).isNull();
        assertThat(Scopes.current().getTags().get("user.roles")).contains("ROLE_ADMIN");
    }

    @Test
    void sendDefaultPii_emailPrincipal_stays_whenOptedIn() {
        init(true);
        // The 3-arg constructor flips authenticated=true; the 2-arg form
        // creates an un-authenticated token which the enricher correctly
        // ignores.
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user@example.com", null, List.of()));

        AllStakSecurityUserEnricher.apply();

        assertThat(Scopes.current().getUser().getEmail()).isEqualTo("user@example.com");
    }

    @Test
    void anonymous_noOp() {
        init(false);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of()));
        AllStakSecurityUserEnricher.apply();
        assertThat(Scopes.current().getUser()).isNull();
    }
}
