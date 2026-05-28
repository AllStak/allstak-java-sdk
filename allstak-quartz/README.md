# allstak-quartz

Quartz `JobListener` that emits a cron check-in (in-progress → ok /
error) for every job execution.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-quartz</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

### Spring Boot

The starter registers an `AllStakQuartzJobListener` and attaches it to
every `Scheduler` bean it discovers. Disable with
`allstak.capture-quartz=false`.

### Plain

```java
scheduler.getListenerManager().addJobListener(new AllStakQuartzJobListener());
```

## Captures

- Slug = `<groupName>/<jobName>`.
- A fresh isolation scope per execution carrying
  `quartz.group`, `quartz.name`, `quartz.fireInstanceId` tags.
- Status: `ok` (success or vetoed) / `error` (`JobExecutionException`).
- The exception itself is captured via `AllStak.captureException`.
