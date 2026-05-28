# allstak-mongodb

MongoDB driver `CommandListener`. One span per command
(`find`, `insert`, `aggregate`, …) plus the database name. Documents
are not captured.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-mongodb</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
MongoClientSettings.builder()
    .addCommandListener(new AllStakMongoCommandListener())
    .build();
```
