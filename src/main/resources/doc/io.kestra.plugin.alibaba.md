# Alibaba Cloud plugin

Tasks to integrate Kestra flows with Alibaba Cloud services.

## OSS

The `io.kestra.plugin.alibaba.oss` tasks move files between Kestra internal storage and Object Storage Service (OSS) buckets.

### Authentication

Provide an AccessKey ID and secret, and optionally a Security Token Service (STS) token for temporary credentials. Store them as Kestra secrets:

```yaml
accessKeyId: "{{ secret('ALIBABA_ACCESS_KEY_ID') }}"
accessKeySecret: "{{ secret('ALIBABA_ACCESS_KEY_SECRET') }}"
region: cn-hangzhou
```

The endpoint defaults to `https://oss-<region>.aliyuncs.com`. Use `endpointOverride` to target another endpoint, and `pathStyleAccess: true` for emulators addressed by IP or hostname.

### Available tasks

- `Upload`: upload a `kestra://` file to a bucket key, outputs `etag` and `key`.
- `Download`: download an object to internal storage, outputs `uri` and `size`.

Run `./gradlew lintPluginDocs` before pushing to validate the plugin documentation.
