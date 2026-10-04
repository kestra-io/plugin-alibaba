# How to use the Alibaba Cloud plugin

This plugin integrates Kestra flows with Alibaba Cloud. It currently covers Object Storage Service (OSS) through the `io.kestra.plugin.alibaba.oss` tasks and Function Compute (FC) through the `io.kestra.plugin.alibaba.fc` tasks. See the [OSS Java SDK reference](https://www.alibabacloud.com/help/en/oss/developer-reference/oss-java-sdk/) and the [Function Compute 3.0 SDK reference](https://www.alibabacloud.com/help/en/functioncompute/fc-3-0/developer-reference/sdk-reference-20230330) for the underlying APIs.

## Authentication

Every task needs an AccessKey pair, and a Security Token Service (STS) token when you use temporary credentials.

- `accessKeyId`: the AccessKey ID.
- `accessKeySecret`: the AccessKey secret.
- `securityToken`: optional STS token.
- `region`: the region ID, for example `cn-hangzhou`. The endpoint defaults to `https://oss-<region>.aliyuncs.com` for OSS and `https://<accountId>.<region>.fc.aliyuncs.com` for Function Compute.
- `accountId`: your Alibaba Cloud account ID, required by Function Compute tasks unless `endpointOverride` is set.
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

### `io.kestra.plugin.alibaba.fc.Invoke`

Invokes a Function Compute function and fails on a non-2xx response.

- Required: `functionName`, and `accountId` unless `endpointOverride` is set.
- Optional: `payload` (map sent as JSON), `invocationType` (`SYNC` by default, or `ASYNC` to queue the event and return immediately), `qualifier` (version or alias, `LATEST` by default).
- Outputs: `statusCode`, `body` (empty for `ASYNC`), `requestId`.
