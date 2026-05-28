# allstak-spring-security

Reads `SecurityContextHolder` and writes the authenticated principal
into the current AllStak scope's user.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-spring-security</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

### Spring Boot

The starter registers a `HandlerInterceptor` that calls
`AllStakSecurityUserEnricher.apply()` on every request before the
controller method runs. Disable with `allstak.capture-security-user=false`.

### Plain

```java
// inside your filter / request lifecycle
AllStakSecurityUserEnricher.apply();
```

## PII

- Default (`sendDefaultPii=false`): only the opaque principal name is
  stored.
- Opt-in (`sendDefaultPii=true`): an email-shaped principal becomes
  the user's email too.
- Granted authorities go into the `user.roles` tag, comma-separated.
- Anonymous and unauthenticated tokens are skipped.
