# AllStak Spring Boot Demo

Minimal Spring Boot application demonstrating the AllStak Java SDK.

## Prerequisites

- Java 17+
- Maven 3.8+
- AllStak API key

## Setup

1. Install the AllStak SDK to your local Maven cache:

```bash
cd ../../
mvn install -DskipTests -q
```

2. Set your API key:

```bash
export ALLSTAK_API_KEY=ask_live_your_key_here
# Optional: point to a custom host
# export ALLSTAK_HOST=http://localhost:8080
```

3. Run the demo:

```bash
cd examples/spring-boot-demo
mvn spring-boot:run
```

The app starts on port 8081.

## Endpoints

| Endpoint | Description |
| -------- | ---------------------------------------------------------------- |
| `GET /` | Returns "AllStak Spring Boot Demo" |
| `GET /error` | Throws a RuntimeException (captured by AllStak automatically) |
| `GET /trace` | Makes an outbound HTTP call to httpbin.org (traced by AllStak) |
| `GET /db` | Queries an in-memory H2 database (queries captured by AllStak) |
| `GET /log` | Emits log messages at DEBUG, INFO, WARN, ERROR levels |

## What to look for

After hitting the endpoints, open the AllStak dashboard to see:

- **Errors** tab: the RuntimeException from `/error`
- **HTTP** tab: inbound requests and the outbound call from `/trace`
- **Database** tab: SQL queries from `/db`
- **Logs** tab: structured log entries from `/log`
