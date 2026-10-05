# How to use the Alibaba Cloud plugin

Integrate your Kestra workflows with Alibaba Cloud services, including MaxCompute for data warehousing and Simple Log Service (SLS) for log querying, ingestion, and event triggering.

## Authentication

All tasks authenticate with Alibaba Cloud using standard credentials:

- `accessKeyId`: Alibaba Cloud AccessKey ID.
- `accessKeySecret`: Alibaba Cloud AccessKey Secret (stored securely and masked in outputs).
- `securityToken`: Optional Security Token Service (STS) temporary token.

When defining credentials in tasks, always store sensitive keys using Kestra [secrets](https://kestra.io/docs/concepts/secret):

```yaml
accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
```

## Common Properties

Set `region` on each task (for example, `cn-hangzhou`, `cn-shanghai`, `ap-southeast-1`). Tasks also support `endpoint` for connecting to custom or private VPC endpoints.

## Tasks and Triggers

### MaxCompute (ODPS)

MaxCompute provides petabyte-scale cloud data warehousing and big data processing.

- `io.kestra.plugin.alibaba.maxcompute.Query`: Executes SQL statements against MaxCompute tables. Supports `FETCH_ONE`, `FETCH`, `STORE` (streaming to Kestra internal storage), and `NONE` fetch types. Supports task cancellation (`kill()`).
- `io.kestra.plugin.alibaba.maxcompute.Trigger`: Polling trigger that detects new rows in a MaxCompute table using a watermark column stored in Kestra namespace KV store.

#### Example: Run SQL on MaxCompute

```yaml
id: maxcompute_query
namespace: company.team

tasks:
  - id: run_analytics
    type: io.kestra.plugin.alibaba.maxcompute.Query
    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
    region: cn-hangzhou
    project: analytics_project
    sql: "SELECT user_id, count(*) AS total FROM user_events GROUP BY user_id;"
    fetchType: FETCH
```

### Simple Log Service (SLS)

Simple Log Service is Alibaba Cloud's all-in-one logging service for log collection, querying, consumption, and real-time processing.

- `io.kestra.plugin.alibaba.sls.Query`: Queries logs from a logstore using SQL/Search queries, with time ranges and `FETCH_ONE`, `FETCH`, or `STORE` fetch modes.
- `io.kestra.plugin.alibaba.sls.Push`: Ingests log records into a logstore from inline maps or from files in Kestra internal storage.
- `io.kestra.plugin.alibaba.sls.Trigger`: Polling trigger that queries for new log records matching a query filter and maintains an epoch cursor in Kestra namespace KV store.

#### Example: Query Logs from SLS

```yaml
id: sls_query_flow
namespace: company.team

tasks:
  - id: query_errors
    type: io.kestra.plugin.alibaba.sls.Query
    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
    region: cn-hangzhou
    project: production_logs
    logstore: app_stdout
    from: "-15m"
    to: "now"
    query: "level: ERROR"
    fetchType: FETCH
```

#### Example: Push Logs to SLS

```yaml
id: sls_push_flow
namespace: company.team

tasks:
  - id: ingest_logs
    type: io.kestra.plugin.alibaba.sls.Push
    accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
    accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
    region: cn-hangzhou
    project: production_logs
    logstore: audit_trail
    from:
      - message: "Deployment completed"
        environment: "production"
      - message: "Cache invalidated"
        environment: "production"
```
