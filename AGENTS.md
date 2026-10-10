# Kestra Alibaba Cloud Plugin

## What

- Provides plugin components under `io.kestra.plugin.alibaba`.
- Includes classes such as `Upload`, `Download`, `List`, `Delete`, `Copy`, `Trigger`.

## Why

- This plugin integrates Kestra with Alibaba Cloud OSS.
- It moves files between Kestra internal storage and OSS buckets with typed properties, secret masking and outputs that plug into downstream tasks.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.alibaba`:

- `oss`

The root package holds the shared connection base (`AlibabaConnectionInterface`, `AbstractConnection`). The `oss` package holds `AbstractOss`, which builds the OSS client, and the tasks.

Tests run against a WireMock server standing in for the OSS endpoint, so no cloud credentials are needed.

### Key Plugin Classes

- `io.kestra.plugin.alibaba.oss.Upload`
- `io.kestra.plugin.alibaba.oss.Download`
- `io.kestra.plugin.alibaba.oss.List`
- `io.kestra.plugin.alibaba.oss.Delete`
- `io.kestra.plugin.alibaba.oss.Copy`
- `io.kestra.plugin.alibaba.oss.Trigger`

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
