# allstak-maven-plugin

Maven plugin for AllStak source-context support. Generate a debug id,
bundle the project's `.java` sources, and upload the bundle to AllStak
so the dashboard can symbolicate stack frames into readable source.

## Goals

| Goal | Default phase | Purpose |
|---|---|---|
| `generate-debug-id` | `generate-resources` | Writes a UUID into `target/generated-resources/allstak/allstak-debug-meta.properties`. The runtime SDK reads it and attaches `debug.id` to every captured event. |
| `upload-source-bundle` | `package` | Zips every `.java` under the project's compile source roots and POSTs the bundle to AllStak. The debug id from the previous goal links the two artefacts. |

## Wire

```xml
<plugin>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-maven-plugin</artifactId>
    <version>${allstak.version}</version>
    <executions>
        <execution>
            <id>allstak-debug-id</id>
            <goals><goal>generate-debug-id</goal></goals>
        </execution>
        <execution>
            <id>allstak-source-bundle</id>
            <goals><goal>upload-source-bundle</goal></goals>
            <configuration>
                <apiKey>${env.ALLSTAK_API_KEY}</apiKey>
                <skip>${allstak.upload.skip}</skip>
            </configuration>
        </execution>
    </executions>
</plugin>
```

## Properties

- `allstak.api.key` — project-scoped ingest API key.
- `allstak.upload.url` — override the default upload endpoint
  (`https://api.allstak.sa/api/v1/admin/source-bundles`).
- `allstak.debug.id` — reuse an existing debug id instead of minting
  a fresh one.
- `allstak.upload.skip` — skip the upload (useful on snapshot builds).
