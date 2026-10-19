# How to use the Alibaba Cloud plugin

This plugin integrates Kestra flows with Alibaba Cloud. It covers OSS file transfers, MaxCompute SQL, Simple Log Service (SLS) log ingestion, queries, and polling triggers, Function Compute (FC) invocations, Elastic Compute Service (ECS) instances, and Message Service (MNS) queues. See the [OSS Java SDK reference](https://www.alibabacloud.com/help/en/oss/developer-reference/oss-java-sdk/) for the underlying API.

## Authentication

Every task needs an AccessKey pair, and a Security Token Service (STS) token when you use temporary credentials.

- `accessKeyId`: the AccessKey ID.
- `accessKeySecret`: the AccessKey secret.
- `securityToken`: optional STS token.
- `region`: the region ID, for example `cn-hangzhou`. Default endpoints are `https://oss-<region>.aliyuncs.com`, `https://service.<region>.maxcompute.aliyun.com/api`, `https://<region>.log.aliyuncs.com`, `https://<accountId>.<region>.fc.aliyuncs.com`, `https://ecs.<region>.aliyuncs.com`, and `https://<accountId>.mns.<region>.aliyuncs.com`. ECS and MNS tasks always require `region`.
- `accountId`: your Alibaba Cloud account ID, required by Function Compute and MNS unless `endpointOverride` is set.
- `endpointOverride`: optional URL that replaces the region-derived endpoint. OSS requires https unless `pathStyleAccess` is true. MaxCompute and SLS also accept explicit http URLs for local endpoints. For SLS, specify the full service endpoint, including the project hostname for private endpoints; an explicit port is preserved. Treat it as trusted input, because signed requests and the STS token are sent to it.

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

### `io.kestra.plugin.alibaba.maxcompute.Query`

Runs SQL and waits for success. Cancellation stops the remote instance, including cancellation during submission.

- Required: `project`, `sql`, credentials, and either `region` or `endpointOverride`.
- `fetchType`: `NONE` (default), `FETCH`, `FETCH_ONE`, or `STORE`.
- `tunnelEndpoint`: optional instance result tunnel URL; applies to result downloads.
- Outputs: `instanceId`, `size`, and `rows`, `row`, or `uri`. `FETCH_ONE` reads at most one row, so its size is 0 or 1.
- `STORE` writes incrementally to Ion using the SDK's bounded result iterator. The RAM principal needs result tunnel download permissions.

```yaml
id: maxcompute_query
namespace: company.team

tasks:
  - id: query
    type: io.kestra.plugin.alibaba.maxcompute.Query
    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
    region: cn-shanghai
    project: analytics
    sql: SELECT id, amount FROM orders
    fetchType: STORE
```

### `io.kestra.plugin.alibaba.maxcompute.Trigger`

Polls SQL and produces one execution per non-empty batch. Required `watermarkField` must be a monotonically increasing, non-null column included in every row. `initialWatermark` defaults to empty; use a lower bound such as `0` for numeric IDs. Numeric values compare numerically, ISO instants chronologically, and other strings lexicographically. Type and value are persisted together in namespace KV.

Use a strict greater-than filter and ascending order. Do not split rows sharing a watermark across batches: a subsequent strict filter skips the remaining ties. Prefer a unique increasing column when timestamps are not unique. Late inserts below the committed watermark are outside this trigger's contract.

`trigger.watermark` is text interpolated into SQL, escaped for a single-quoted literal. Surround it with quotes in SQL. `FETCH` and `STORE` both advance from delivered rows. KV read and write failures fail the poll.

```yaml
id: maxcompute_new_orders
namespace: company.team

tasks:
  - id: report
    type: io.kestra.plugin.core.log.Log
    message: "{{ trigger.size }} new orders"

triggers:
  - id: orders
    type: io.kestra.plugin.alibaba.maxcompute.Trigger
    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
    region: cn-shanghai
    project: analytics
    sql: SELECT id, amount FROM orders WHERE id > '{{ trigger.watermark }}' ORDER BY id ASC LIMIT 50
    watermarkField: id
    initialWatermark: "0"
    fetchType: STORE
    interval: PT1M
```

### `io.kestra.plugin.alibaba.sls.Query`

Queries one page of logs by search filter or SQL analysis. Set `project` and `logstore`; `query` and `topic` default to empty. `from` and `to` accept ISO instants or epoch seconds, defaulting to the past 15 minutes through now. Invalid times fail instead of silently changing the window.

`lines` is 1–100 (default 100); `offset` is non-negative (default 0). Use offsets for additional search pages. Incomplete responses fail. `FETCH` (default), `FETCH_ONE`, `STORE`, and `NONE` return `count` and applicable `logs`, `log`, or `uri`.

### `io.kestra.plugin.alibaba.sls.Push`

Ingests logs into `project` / `logstore`. `from` uses Kestra's `Data.From` contract: inline maps, lists, JSON strings, or supported data URIs. Records are consumed with `Data.from(from).read(runContext)` and sent in batches of at most 4,096. Internal storage reads follow the runtime's Ion format, including binary Ion on Kestra 2.0.

`__time__` accepts epoch seconds; `timestamp` accepts an ISO instant. Absent timestamps use current time. Invalid values fail with the record index and field name. SLS's unsigned 32-bit epoch range is enforced, including dates after 2038.

```yaml
id: sls_push
namespace: company.team

tasks:
  - id: push
    type: io.kestra.plugin.alibaba.sls.Push
    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
    region: cn-hangzhou
    project: ops-logs
    logstore: app-events
    from:
      - level: INFO
        message: Flow finished
```

### `io.kestra.plugin.alibaba.sls.Trigger`

Consumes log groups by shard ingestion position, persisting a map of shard IDs to opaque cursors in namespace KV. Each poll snapshots shard end cursors and consumes all pages up to them. Newly ingested logs with older event timestamps are included. New shards start at `lookback` (default `PT5M`); readonly shards returned by SLS after splits are also consumed.

`query` is an SLS SPL consumption filter, such as `* | where level == 'ERROR'`, rather than index search or SQL analysis. `topic` optionally filters groups. `FETCH` and `STORE` return `count` and `logs` or `uri`; STORE writes incrementally. Clients shut down after every poll. A failed shard page, output upload, execution construction, or KV operation fails the poll. Cursors commit together only after all pages succeed.

Use `fetchType: STORE` for high-volume logstores, long lookback windows, or catch-up after downtime. The default `FETCH` mode accumulates the complete matching backlog across all shard pages in worker memory and execution variables. It is suitable for small log batches. `STORE` writes results incrementally to internal storage and returns their URI.

```yaml
id: sls_on_errors
namespace: company.team

tasks:
  - id: alert
    type: io.kestra.plugin.core.log.Log
    message: "{{ trigger.count }} error logs received"

triggers:
  - id: errors
    type: io.kestra.plugin.alibaba.sls.Trigger
    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
    region: cn-hangzhou
    project: ops-logs
    logstore: app-events
    query: "* | where level == 'ERROR'"
    fetchType: STORE
    interval: PT1M
```

Both triggers use length-prefixed state keys. State commits and scheduler execution persistence are separate operations, so downstream processing should be idempotent across scheduler crashes; these triggers do not claim transactional exactly-once delivery. Cursor expiry surfaces as an error rather than resetting state and replaying logs.

### `io.kestra.plugin.alibaba.fc.Invoke`

Invokes a [Function Compute 3.0](https://www.alibabacloud.com/help/en/functioncompute/fc-3-0/developer-reference/sdk-reference-20230330) function. The task fails on a non-2xx response, and also when the function itself throws, which Function Compute reports with status 200 and an `X-Fc-Error-Type` header.

- Required: `functionName`, and `accountId` unless `endpointOverride` is set.
- Optional: `payload` (a map, list, number or boolean sent as JSON, or a string sent as is), `invocationType` (`SYNC` by default, or `ASYNC` to queue the event and return immediately), `qualifier` (version or alias, `LATEST` by default).
- Timeout: a call waits for the response up to 90% of the task `timeout` (so the plugin error, not the generic Kestra timeout, is shown), or 10 minutes when it is not set. For longer functions, raise `timeout` or use `ASYNC`.
- Outputs: `statusCode`, `requestId`, `uri` (the response body in internal storage), and `body` (the response as text, only when it is at most 512 KB; empty for `ASYNC`).

### `io.kestra.plugin.alibaba.ecs.List`

Lists the instances of a region with the [DescribeInstances](https://www.alibabacloud.com/help/en/ecs/developer-reference/api-ecs-2014-05-26-describeinstances) API, following pagination. Each instance has the API fields, for example `InstanceId`, `InstanceName` and `Status`.

- Required: `region`.
- Optional: `instanceIds` (up to 100), `status` (`PENDING`, `RUNNING`, `STARTING`, `STOPPING`, `STOPPED`), `instanceName` (supports `*`), `fetchType` (`FETCH` by default, `FETCH_ONE`, `STORE`, `NONE`).
- Outputs: `rows`, `row`, `uri` or `size` depending on `fetchType`. With `STORE`, each page is written to the file as it arrives.

Each ECS API call waits up to 90% of the task `timeout` (so the plugin error, not the generic Kestra timeout, is shown), or 30 seconds when it is not set. When `Start`, `Stop` or `Reboot` gets no response in time, the request may already have been accepted, so check the instance status before retrying.

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

If one instance of a `Start`, `Stop` or `Reboot` batch is rejected, the task fails and logs which instances were accepted, since the API has already acted on them.

### MNS message bodies and delivery

MNS tasks and triggers use the [MNS SDK](https://www.alibabacloud.com/help/en/mns/developer-reference/foreword), which calls the endpoint with its own HTTP client, so Kestra's HTTP client allow and deny lists do not apply to `endpointOverride`.

Message bodies are sent and read as raw strings, which is what the MNS console and HTTP API use. The Java SDK's default `setMessageBody(String)` Base64-encodes bodies: decode such messages in your flow with the `base64decode` Pebble filter.

`Consume` stores the received messages first and only then deletes them, so the stored file always holds every deleted message. Keep `maxDuration` below the queue's visibility timeout (30 seconds by default): a message whose receipt handle expired before the deletion is delivered again, and the failed deletion is logged as a warning. `Trigger` deletes the messages once the file is stored, before the scheduler saves the execution, so a failure in between loses them; set `autoDelete: false` to keep them in the queue until their visibility timeout ends. `RealtimeTrigger` deletes the messages of a poll once Kestra has taken them and requests more, so a stop or kill never deletes a message that was not handed over, although a crash right after the hand-over can still lose it.

MNS requests use a 10 second connect timeout and a 45 second socket timeout, above the 30 second maximum long-poll wait, with up to 3 retries.

### `io.kestra.plugin.alibaba.mns.Publish`

Sends messages to a queue in batches of 16.

- Required: `queue`, `from` (a message, a list of messages, or a `kestra://` ION file with one message per row). Each message has a `data` field (a string, or a map or list sent as JSON) and optional `delaySeconds` (0 to 604800) and `priority` (1 to 16).
- Inline messages are rendered once by Kestra. Rows read from a `kestra://` file are sent as is, so file content is never evaluated as a template.
- If a batch fails, the error says how many messages were already published: a retry publishes them again.
- Outputs: `messagesCount`.

### `io.kestra.plugin.alibaba.mns.Consume`

Receives messages until the queue is empty, `maxRecords` is reached or `maxDuration` elapses, and stores them in an ION file.

- Required: `queue`.
- Optional: `maxDuration` (20 seconds by default), `maxRecords` (1000 by default, at least 1), `serdeType` (`STRING` by default, or `JSON`; a body that is not valid JSON is kept as a string and logged), `autoDelete` (`true` by default).
- Outputs: `count`, and `uri` when at least one message was received.

### `io.kestra.plugin.alibaba.mns.Trigger`

Polls a queue every `interval` (60 seconds by default) and starts one execution per poll that received messages, with the same options as `Consume`.

- Required: `queue`.
- Outputs: `trigger.count`, `trigger.uri`.

### `io.kestra.plugin.alibaba.mns.RealtimeTrigger`

Long-polls a queue and starts one execution per message. It only polls when executions are requested, so messages are never buffered without bound.

- Required: `queue`.
- Optional: `serdeType`, `autoDelete`, `waitTime` (20 seconds by default, from 1 to 30), `batchSize` (16 by default, from 1 to 16).
- Outputs: `trigger.data`, `trigger.messageId`.
