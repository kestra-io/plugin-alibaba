# How to use the Alibaba Cloud plugin

This plugin integrates Kestra flows with Alibaba Cloud. It currently covers Object Storage Service (OSS) through the `io.kestra.plugin.alibaba.oss` tasks. See the [OSS Java SDK reference](https://www.alibabacloud.com/help/en/oss/developer-reference/oss-java-sdk/) for the underlying API.

## Authentication

Every task needs an AccessKey pair, and a Security Token Service (STS) token when you use temporary credentials.

- `accessKeyId`: the AccessKey ID.
- `accessKeySecret`: the AccessKey secret.
- `securityToken`: optional STS token.
- `region`: the region ID, for example `cn-hangzhou`. The endpoint defaults to `https://oss-<region>.aliyuncs.com`.
- `endpointOverride`: optional URL that replaces the region-derived endpoint. It must use https, unless `pathStyleAccess` is true, which allows plain http for local emulators. Treat it as trusted input, because signed requests and the STS token are sent to it.

Role-based credentials (ECS instance roles and RAM roles) are not supported.

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

### `io.kestra.plugin.alibaba.oss.List`

Lists the objects of a bucket. Folder placeholder keys (ending with `/`) are skipped.

- Required: `bucket`.
- Optional: `prefix`, `delimiter`, `regexp` (the whole key must match, at most 256 characters, evaluation is time-boxed), `maxFiles` (1 to 1000, default 25, a warning is logged when more objects match), `pathStyleAccess`.
- Outputs: `objects` with `key`, `size`, `etag`, `lastModified`, `storageClass`.

### `io.kestra.plugin.alibaba.oss.Delete`

Deletes an object. OSS reports success for a missing key.

- Required: `bucket`, `key`.
- Optional: `pathStyleAccess`.
- Outputs: `key`.

### `io.kestra.plugin.alibaba.oss.Copy`

Copies an object in a single request, so it is limited to objects of at most 1 GB, which is checked before copying. If the source cannot be deleted after a successful copy, the task fails and says the copy exists.

- Required: `bucket`, `key`, `destinationKey`.
- Optional: `destinationBucket` (defaults to `bucket`), `delete` (delete the source afterwards, which moves the object), `pathStyleAccess`.
- Outputs: `bucket`, `key`, `etag`.

### `io.kestra.plugin.alibaba.oss.Trigger`

Polls a bucket every `interval` (default 60 seconds), downloads up to `maxFiles` matching objects into Kestra internal storage and starts one execution with all of them. Objects are downloaded first, then each is deleted or moved so it is not picked up again. The execution starts even if the delete or move of some objects fails, and those objects are reported again by the next poll. An object that cannot be downloaded is left in place.

- Required: `bucket`, `action` (`DELETE` or `MOVE`).
- Required for `MOVE`: `moveToPrefix`, prepended to the original key. The task fails when a moved key in the same bucket would still match `prefix` and `regexp`. `moveToBucket` defaults to `bucket`.
- Optional: `prefix`, `regexp`, `maxFiles`, `interval`, `pathStyleAccess`.
- Outputs: `objects` with `key`, `size`, `etag`, `lastModified`, `uri` (the downloaded file in internal storage) and `actionFailed` (true when the delete or move failed and the object is still in the bucket).
