# How to use the Alibaba Cloud plugin

This plugin integrates Kestra flows with Alibaba Cloud. It currently covers Object Storage Service (OSS) through the `io.kestra.plugin.alibaba.oss` tasks and Elastic Compute Service (ECS) through the `io.kestra.plugin.alibaba.ecs` tasks. See the [OSS Java SDK reference](https://www.alibabacloud.com/help/en/oss/developer-reference/oss-java-sdk/) and the [ECS API reference](https://www.alibabacloud.com/help/en/ecs/developer-reference/api-ecs-2014-05-26-overview) for the underlying APIs.

## Authentication

Every task needs an AccessKey pair, and a Security Token Service (STS) token when you use temporary credentials.

- `accessKeyId`: the AccessKey ID.
- `accessKeySecret`: the AccessKey secret.
- `securityToken`: optional STS token.
- `region`: the region ID, for example `cn-hangzhou`. The endpoint defaults to `https://oss-<region>.aliyuncs.com` for OSS and `https://ecs.<region>.aliyuncs.com` for ECS. Required for ECS tasks.
- `endpointOverride`: optional http or https URL that replaces the region-derived endpoint, for example for an emulator. Treat it as trusted input.

Store the credentials as [secrets](https://kestra.io/docs/concepts/secret) and set them once for all tasks with [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults):

```yaml
pluginDefaults:
  - type: io.kestra.plugin.alibaba.oss
    values:
      accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
      accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
      region: cn-hangzhou
```

## Tasks

### `io.kestra.plugin.alibaba.oss.Upload`

Uploads a file from Kestra internal storage to a bucket. The file is sent in a single request, so it is limited to 5 GB.

- Required: `bucket`, `key`, `from`.
- Optional: `pathStyleAccess` (address buckets as `<endpoint>/<bucket>`, useful with emulators).
- Outputs: `etag`, `key`.

### `io.kestra.plugin.alibaba.oss.Download`

Downloads an object into Kestra internal storage.

- Required: `bucket`, `key`.
- Optional: `pathStyleAccess`.
- Outputs: `uri`, `size`.

### `io.kestra.plugin.alibaba.ecs.List`

Lists the ECS instances of a region, following pagination. Each instance has the fields of the DescribeInstances API, for example `InstanceId`, `InstanceName` and `Status`.

- Required: `region`.
- Optional: `instanceIds` (up to 100), `status` (`PENDING`, `RUNNING`, `STARTING`, `STOPPING`, `STOPPED`), `instanceName` (supports `*`), `fetchType` (`FETCH` by default, `FETCH_ONE`, `STORE`, `NONE`).
- Outputs: `rows`, `row`, `uri` or `size` depending on `fetchType`.

### `io.kestra.plugin.alibaba.ecs.Start`

Starts stopped instances. The task returns once the request is accepted, while instances are still `Starting`.

- Required: `region`, `instanceIds` (up to 100).
- Outputs: `requestId`, `instances` (`instanceId`, `previousStatus`, `currentStatus`).

### `io.kestra.plugin.alibaba.ecs.Stop`

Stops running instances. The task returns once the request is accepted, while instances are still `Stopping`.

- Required: `region`, `instanceIds` (up to 100).
- Optional: `forceStop` (default `false`), `stoppedMode` (`KEEP_CHARGING` or `STOP_CHARGING`).
- Outputs: `requestId`, `instances`.

### `io.kestra.plugin.alibaba.ecs.Reboot`

Reboots running instances.

- Required: `region`, `instanceIds` (up to 100).
- Optional: `forceReboot` (default `false`).
- Outputs: `requestId`, `instances`.
