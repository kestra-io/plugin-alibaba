# Kestra Alibaba Cloud Plugin

## What

- Provides plugin components under `io.kestra.plugin.alibaba`.
- Includes OSS `Upload`, `Download`, `List`, `Delete`, `Copy`, and `Trigger`, MaxCompute `Query` and `Trigger`, SLS `Query`, `Push`, and `Trigger`, Function Compute `Invoke`, ECS `List`, `Start`, `Stop`, and `Reboot`, and MNS `Publish`, `Consume`, `Trigger`, and `RealtimeTrigger`.

## Why

- This plugin integrates Kestra with Alibaba Cloud OSS, MaxCompute, Simple Log Service, Function Compute, ECS, and Message Service (MNS).
- It moves files through OSS, runs MaxCompute SQL, and queries or ingests SLS logs with typed properties and secret masking. MaxCompute and SLS polling triggers persist typed watermarks or shard cursors in namespace KV. Function Compute `Invoke` calls a function synchronously or asynchronously and exposes the response to downstream tasks. ECS tasks list, start, stop and reboot instances so flows can manage compute around their workloads. MNS tasks publish and consume queue messages, and the MNS triggers start flows when messages arrive.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin.alibaba`:

- `oss`
- `maxcompute`
- `sls`
- `fc`
- `ecs`
- `mns`

The root package holds the shared connection base (`AlibabaConnectionInterface`, `AbstractConnection`). The `oss` package holds `AbstractOss`, which builds the OSS client, and the tasks. The `fc` package holds `Invoke`, which builds its Function Compute client from the shared connection properties plus `accountId`. The `ecs` package holds `AbstractEcs`, which builds the ECS client, `AbstractInstanceAction`, shared by `Start`, `Stop` and `Reboot`, and `List`. The `mns` package holds `AbstractMns`, which builds the MNS client, the `Publish` and `Consume` tasks, and `AbstractMnsTrigger`, shared by `Trigger` and `RealtimeTrigger`.

Tests exercise OSS, MaxCompute instance/result-tunnel APIs, SLS, Function Compute, ECS and MNS endpoints through WireMock, so no cloud credentials are needed.

### Key Plugin Classes

- `io.kestra.plugin.alibaba.oss.Upload`
- `io.kestra.plugin.alibaba.oss.Download`
- `io.kestra.plugin.alibaba.oss.List`
- `io.kestra.plugin.alibaba.oss.Delete`
- `io.kestra.plugin.alibaba.oss.Copy`
- `io.kestra.plugin.alibaba.oss.Trigger`
- `io.kestra.plugin.alibaba.maxcompute.Query`
- `io.kestra.plugin.alibaba.maxcompute.Trigger`
- `io.kestra.plugin.alibaba.sls.Query`
- `io.kestra.plugin.alibaba.sls.Push`
- `io.kestra.plugin.alibaba.sls.Trigger`
- `io.kestra.plugin.alibaba.fc.Invoke`
- `io.kestra.plugin.alibaba.ecs.List`
- `io.kestra.plugin.alibaba.ecs.Start`
- `io.kestra.plugin.alibaba.ecs.Stop`
- `io.kestra.plugin.alibaba.ecs.Reboot`
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
