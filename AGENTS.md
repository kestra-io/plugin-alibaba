# Alibaba Cloud Plugin - MaxCompute and Simple Log Service (SLS)

## What

- Provides tasks and triggers for Alibaba Cloud MaxCompute under `io.kestra.plugin.alibaba.maxcompute`:
  - `Query`: Executes SQL statements against MaxCompute tables with multiple fetch types (`FETCH_ONE`, `FETCH`, `STORE`) and task cancellation via `kill()`.
  - `Trigger`: Polling trigger detecting new rows in MaxCompute tables using stateful watermarks persisted in the namespace KV store.
- Provides tasks and triggers for Alibaba Cloud Simple Log Service under `io.kestra.plugin.alibaba.sls`:
  - `Query`: Queries logstores over time ranges with `FETCH`, `FETCH_ONE`, or `STORE` modes.
  - `Push`: Ingests log entries into an SLS logstore from inline collections or internal storage files.
  - `Trigger`: Polling trigger on new log entries using a persisted epoch cursor in the namespace KV store.

## Why

- What user problem does this solve? Users previously relied on external CLIs (`odpscmd`, SLS CLI) or custom scripts with plain-text credentials to interact with Alibaba Cloud data warehouse and log services.
- Why would a team adopt this plugin in a workflow? Native tasks provide typed properties, secret masking, stored result sets, cancellation handling, and exactly-once event triggers.
- What operational/business outcome does it enable? Enables data engineers and SRE teams to orchestrate end-to-end data pipelines and reactive alerting workflows on Alibaba Cloud.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.alibaba`:

- `maxcompute`
- `sls`

### Key Plugin Classes

- `io.kestra.plugin.alibaba.maxcompute.Query`
- `io.kestra.plugin.alibaba.maxcompute.Trigger`
- `io.kestra.plugin.alibaba.sls.Push`
- `io.kestra.plugin.alibaba.sls.Query`
- `io.kestra.plugin.alibaba.sls.Trigger`

### Project Structure

```
plugin-alibaba/
├── src/main/java/io/kestra/plugin/alibaba/
│   ├── maxcompute/
│   │   ├── Query.java
│   │   ├── Trigger.java
│   │   └── package-info.java
│   ├── sls/
│   │   ├── Push.java
│   │   ├── Query.java
│   │   ├── Trigger.java
│   │   └── package-info.java
│   ├── AbstractConnection.java
│   └── AlibabaConnectionInterface.java
├── src/test/java/io/kestra/plugin/alibaba/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
