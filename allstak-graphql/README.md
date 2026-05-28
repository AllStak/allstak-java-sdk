# allstak-graphql

`graphql-java` `Instrumentation` extension. One span per GraphQL
operation; exceptions thrown by data fetchers are captured.

## Install

```xml
<dependency>
    <groupId>sa.allstak</groupId>
    <artifactId>allstak-graphql</artifactId>
    <version>${allstak.version}</version>
</dependency>
```

## Use

```java
GraphQL graphQL = GraphQL.newGraphQL(schema)
    .instrumentation(new AllStakGraphqlInstrumentation())
    .build();
```

Field-level spans are intentionally not emitted — they explode
cardinality on large queries.
