# Kestra Alibaba Cloud Plugin

## What

- Provides plugin components under `io.kestra.plugin.alibaba`.
- Includes classes such as `Upload`, `Download`, `Invoke`.

## Why

- This plugin integrates Kestra with Alibaba Cloud OSS and Function Compute.
- It moves files between Kestra internal storage and OSS buckets with typed properties, secret masking and outputs that plug into downstream tasks.
- It invokes Function Compute functions synchronously or asynchronously and exposes the response status and body to downstream tasks.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.alibaba`:

- `oss`
- `fc`

The root package holds the shared connection base (`AlibabaConnectionInterface`, `AbstractConnection`). The `oss` package holds `AbstractOss`, which builds the OSS client, and the tasks. The `fc` package holds `Invoke`, which builds its Function Compute client from the shared connection properties plus `accountId`.

Tests run against a WireMock server standing in for the OSS and Function Compute endpoints, so no cloud credentials are needed.

### Key Plugin Classes

- `io.kestra.plugin.alibaba.oss.Upload`
- `io.kestra.plugin.alibaba.oss.Download`
- `io.kestra.plugin.alibaba.fc.Invoke`

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
