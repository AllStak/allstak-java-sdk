package dev.allstak.spring_security;

import dev.allstak.AllStak;
import dev.allstak.AllStakClient;
import dev.allstak.model.UserContext;
import dev.allstak.scope.Scopes;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.stream.Collectors;

/**
 * Reads the current {@link SecurityContextHolder} and pushes the
 * authenticated principal into the active AllStak scope's user, and the
 * granted authorities as a comma-separated tag.
 *
 * <p>Honours {@link dev.allstak.AllStakConfig#isSendDefaultPii()}: when
 * false (the default), only the opaque principal name is set; email-like
 * usernames are suppressed.
 */
public final class AllStakSecurityUserEnricher {

    private AllStakSecurityUserEnricher() {}

    /** Pull the current Authentication into the scope. No-op if anonymous or unauthenticated. */
    public static void apply() {
        AllStakClient client = AllStak.getClient();
        if (client == null) return;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return;
        if ("anonymousUser".equals(auth.getPrincipal())) return;

        String id = resolveId(auth);
        if (id == null || id.isBlank()) return;

        UserContext user = client.getConfig().isSendDefaultPii()
                ? UserContext.of(id, emailIfLooksLikeOne(id), null)
                : UserContext.ofId(id);
        Scopes.current().setUser(user);

        String roles = auth.getAuthorities() == null ? null
                : auth.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(Collectors.joining(","));
        if (roles != null && !roles.isBlank()) {
            Scopes.current().setTag("user.roles", roles);
        }
    }

    private static String resolveId(Authentication auth) {
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails ud) return ud.getUsername();
        if (principal instanceof String s) return s;
        return auth.getName();
    }

    private static String emailIfLooksLikeOne(String s) {
        return (s != null && s.contains("@") && s.indexOf('@') > 0 && s.indexOf('@') < s.length() - 1) ? s : null;
    }
}
