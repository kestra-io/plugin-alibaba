# How to use the Alibaba Cloud plugin

This plugin integrates Kestra flows with Alibaba Cloud. It currently covers Object Storage Service (OSS) through the `io.kestra.plugin.alibaba.oss` tasks and Message Service (MNS) queues through the `io.kestra.plugin.alibaba.mns` tasks and triggers. See the [OSS Java SDK reference](https://www.alibabacloud.com/help/en/oss/developer-reference/oss-java-sdk/) and the [MNS developer reference](https://www.alibabacloud.com/help/en/mns/developer-reference/foreword) for the underlying APIs.

## Authentication

Every task needs an AccessKey pair, and a Security Token Service (STS) token when you use temporary credentials.

- `accessKeyId`: the AccessKey ID.
- `accessKeySecret`: the AccessKey secret.
- `securityToken`: optional STS token.
- `region`: the region ID, for example `cn-hangzhou`. The endpoint defaults to `https://oss-<region>.aliyuncs.com` for OSS and `https://<accountId>.mns.<region>.aliyuncs.com` for MNS. Required for MNS.
- `accountId`: your Alibaba Cloud account ID, required by MNS tasks and triggers unless `endpointOverride` is set.
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

### `io.kestra.plugin.alibaba.mns.Publish`

Sends messages to a queue in batches of 16.

- Required: `queue`, `from` (a message, a list of messages, or a `kestra://` ION file with one message per row). Each message has a `data` field (a string, rendered at runtime, or a map or list sent as JSON) and optional `delaySeconds` and `priority`.
- Outputs: `messagesCount`.

### `io.kestra.plugin.alibaba.mns.Consume`

Receives messages until the queue is empty, `maxRecords` is reached or `maxDuration` elapses, and stores them in an ION file.

- Required: `queue`, and `maxRecords` or `maxDuration`.
- Optional: `serdeType` (`STRING` by default, or `JSON`), `autoDelete` (`true` by default).
- Outputs: `count`, `uri`.

### `io.kestra.plugin.alibaba.mns.Trigger`

Polls a queue every `interval` (60 seconds by default) and starts one execution per poll that received messages, with the same options as `Consume`.

- Required: `queue`, and `maxRecords` or `maxDuration`.
- Outputs: `trigger.count`, `trigger.uri`.

### `io.kestra.plugin.alibaba.mns.RealtimeTrigger`

Long-polls a queue and starts one execution per message.

- Required: `queue`.
- Optional: `serdeType`, `autoDelete`, `waitTime` (20 seconds by default, up to 30), `batchSize` (16 by default).
- Outputs: `trigger.data`.
