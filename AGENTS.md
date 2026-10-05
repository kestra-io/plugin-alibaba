# Kestra Alibaba Cloud Plugin

## What

- Provides plugin components under `io.kestra.plugin.alibaba`.
- Includes classes such as `Upload`, `Download`, `Publish`, `Consume`, `Trigger`, `RealtimeTrigger`.

## Why

- This plugin integrates Kestra with Alibaba Cloud OSS and MNS.
- It moves files between Kestra internal storage and OSS buckets with typed properties, secret masking and outputs that plug into downstream tasks.
- It publishes and consumes MNS queue messages and starts flows when messages arrive.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.alibaba`:

- `oss`
- `mns`

The root package holds the shared connection base (`AlibabaConnectionInterface`, `AbstractConnection`). The `oss` package holds `AbstractOss`, which builds the OSS client, and the tasks. The `mns` package holds `AbstractMns`, which builds the MNS client, the `Publish` and `Consume` tasks, and `AbstractMnsTrigger`, shared by `Trigger` and `RealtimeTrigger`, which both run a `Consume` under the hood.

Tests run against a WireMock server standing in for the OSS and MNS endpoints, so no cloud credentials are needed.

### Key Plugin Classes

- `io.kestra.plugin.alibaba.oss.Upload`
- `io.kestra.plugin.alibaba.oss.Download`
- `io.kestra.plugin.alibaba.mns.Publish`
- `io.kestra.plugin.alibaba.mns.Consume`
- `io.kestra.plugin.alibaba.mns.Trigger`
- `io.kestra.plugin.alibaba.mns.RealtimeTrigger`

### Project Structure

```
plugin-alibaba/
├── src/main/java/io/kestra/plugin/alibaba/
├── src/test/java/io/kestra/plugin/alibaba/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
