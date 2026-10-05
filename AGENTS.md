# Kestra Alibaba Cloud Plugin

## What

- Provides plugin components under `io.kestra.plugin.alibaba`.
- Includes classes such as `Upload`, `Download`, `List`, `Start`, `Stop`, `Reboot`.

## Why

- This plugin integrates Kestra with Alibaba Cloud OSS and ECS.
- It moves files between Kestra internal storage and OSS buckets with typed properties, secret masking and outputs that plug into downstream tasks.
- It lists, starts, stops and reboots ECS instances so flows can manage compute around their workloads.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.alibaba`:

- `oss`
- `ecs`

The root package holds the shared connection base (`AlibabaConnectionInterface`, `AbstractConnection`). The `oss` package holds `AbstractOss`, which builds the OSS client, and the tasks. The `ecs` package holds `AbstractEcs`, which builds the ECS client, `AbstractInstanceAction`, shared by `Start`, `Stop` and `Reboot`, and `List`.

Tests run against a WireMock server standing in for the OSS and ECS endpoints, so no cloud credentials are needed.

### Key Plugin Classes

- `io.kestra.plugin.alibaba.oss.Upload`
- `io.kestra.plugin.alibaba.oss.Download`
- `io.kestra.plugin.alibaba.ecs.List`
- `io.kestra.plugin.alibaba.ecs.Start`
- `io.kestra.plugin.alibaba.ecs.Stop`
- `io.kestra.plugin.alibaba.ecs.Reboot`

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
