# Kestra Alibaba Cloud Plugin

## What

- Provides plugin components under `io.kestra.plugin.alibaba`.
- Includes OSS `Upload`, `Download`, `Downloads`, `List`, `Delete`, `Copy`, `CreateBucket`, `DeleteBucket`, and `Trigger`, MaxCompute `Query` and `Trigger`, and SLS `Query`, `Push`, and `Trigger`.

## Why

- This plugin integrates Kestra with Alibaba Cloud OSS, MaxCompute, and Simple Log Service.
- It moves files through OSS, runs MaxCompute SQL, and queries or ingests SLS logs with typed properties and secret masking. MaxCompute and SLS polling triggers persist typed watermarks or shard cursors in namespace KV.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.alibaba`:

- `oss`
- `maxcompute`
- `sls`

The root package holds the shared connection base (`AlibabaConnectionInterface`, `AbstractConnection`). The `oss` package holds `AbstractOss`, which builds the OSS client, and the tasks. `List`, `Download`, `Delete` and `Copy` expose static helpers that take an OSS client, so `Downloads` and `Trigger` reuse them with a single client.

Tests exercise OSS, MaxCompute instance/result-tunnel APIs, and SLS endpoints through WireMock, so no cloud credentials are needed.

### Key Plugin Classes

- `io.kestra.plugin.alibaba.oss.Upload`
- `io.kestra.plugin.alibaba.oss.Download`
- `io.kestra.plugin.alibaba.oss.Downloads`
- `io.kestra.plugin.alibaba.oss.List`
- `io.kestra.plugin.alibaba.oss.Delete`
- `io.kestra.plugin.alibaba.oss.Copy`
- `io.kestra.plugin.alibaba.oss.CreateBucket`
- `io.kestra.plugin.alibaba.oss.DeleteBucket`
- `io.kestra.plugin.alibaba.oss.Trigger`
- `io.kestra.plugin.alibaba.maxcompute.Query`
- `io.kestra.plugin.alibaba.maxcompute.Trigger`
- `io.kestra.plugin.alibaba.sls.Query`
- `io.kestra.plugin.alibaba.sls.Push`
- `io.kestra.plugin.alibaba.sls.Trigger`

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
