# Play Integrity 解码服务（示例）

App 端的「完整性检测」页只负责向 Play 服务要一个**加密 token**，然后把它发到你部署的这个端点。
token 无法在客户端解密，判定只能由服务端用 Google Cloud 服务账号调用
`playintegrity.googleapis.com` 的 `decodeIntegrityToken` 完成。这也是 Play Integrity API Checker
等同类工具的做法。

> 这个目录只是**最小可运行示例**，用来把链路跑通。它没有做鉴权、限流和持久化，请勿直接暴露在公网。

## 1. 准备 Google Cloud

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 新建（或复用）一个项目，记下**项目编号**（纯数字，不是项目 ID）。
2. 启用 **Play Integrity API**：API 与服务 → 库 → 搜索 "Play Integrity" → 启用。
3. 在 **Google Play Console** → 你的应用 → 测试 → 完整性（App integrity）里，把上面这个 Cloud 项目关联到应用。
   未关联时，Play 服务会拒绝 `setCloudProjectNumber` 的请求。
4. 创建服务账号：IAM 与管理 → 服务账号 → 新建，不需要任何项目级角色。
5. 为该服务账号创建 JSON 密钥并下载到**仓库之外**的目录，例如 `~/secrets/integrity-sa.json`。

> 该服务账号只需要能调用 Play Integrity API 的权限；即便泄露，也只能解你自己应用的 token，
> 但仍应视作敏感凭据妥善保管，**绝不要提交进仓库**。

## 2. 取应用签名证书摘要

服务端要校验 token 里的 `certificateSha256Digest`，需要提前知道本应用的签名证书 SHA-256。

```bash
# debug 签名（本项目 release 也复用 debug 签名）
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
  -storepass android -keypass android | grep "SHA256:"
```

输出形如 `SHA256: 11:22:...`，把它去掉冒号并转小写即可。也可以从 App 内「设置 → 完整性检测」
看到当前签名摘要（Debug 版会打印在诊断日志里，或以 `apksigner verify --print-certs` 获取）。

## 3. 运行

```bash
cd server/integrity
python -m venv .venv && source .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install -r requirements.txt

export GOOGLE_APPLICATION_CREDENTIALS=~/secrets/integrity-sa.json
export INTEGRITY_PACKAGE_NAME=dev.mochi.rootbox
export INTEGRITY_CERT_SHA256=<去掉冒号的小写 SHA-256>
export INTEGRITY_MAX_AGE_SECONDS=120
python server.py
```

健康检查：

```bash
curl -s http://127.0.0.1:8080/healthz
# {"ok": true, "package": "dev.mochi.rootbox"}
```

## 4. 接到 App 上

App → 设置 → 完整性检测：

| 字段 | 值 |
| --- | --- |
| 解码服务端点 | `https://<你的域名>/integrity` |
| Google Cloud 项目编号 | 第 1 步的项目编号（纯数字） |

两者都填好之后，「完整性检测」页的按钮才会启用；留空时该页一律显示「无法判定」。

> 手机访问本机服务时不要用 `127.0.0.1`，那指向手机自身。用局域网 IP 或在同一台机器上做端口转发。

## 5. 请求与响应

请求（App → 服务）：

```json
{ "token": "<加密 token>", "nonce": "<base64 nonce>", "packageName": "dev.mochi.rootbox", "certSha256": "<本机签名摘要>" }
```

成功响应：

```json
{
  "ok": true,
  "verdict": {
    "requestPackageName": "dev.mochi.rootbox",
    "appRecognitionVerdict": "PLAY_RECOGNIZED",
    "deviceRecognitionVerdict": ["MEETS_BASIC_INTEGRITY", "MEETS_DEVICE_INTEGRITY"],
    "accountLicensingVerdict": "LICENSED",
    "timestampMillis": 1762732800000,
    "certificateSha256Digest": ["..."],
    "versionCode": "1"
  },
  "derived": { "meetsBasicIntegrity": true, "meetsDeviceIntegrity": true, "meetsStrongIntegrity": false },
  "raw": { "requestDetails": {}, "appIntegrity": {}, "deviceIntegrity": {}, "accountDetails": {} }
}
```

失败响应（此时**不会**包含任何 verdict 字段）：

```json
{ "ok": false, "code": "NONCE_MISMATCH", "error": "token 内的 nonce 与请求不一致" }
```

错误码：

| code | 含义 |
| --- | --- |
| `MISSING_TOKEN` / `MISSING_NONCE` | 请求缺字段 |
| `NONCE_MISMATCH` | token 内 nonce 与请求不一致（疑似重放） |
| `PACKAGE_MISMATCH` | token 里的包名不是本应用 |
| `CERT_MISMATCH` | 签名证书摘要不在白名单 |
| `STALE_TOKEN` / `FUTURE_TOKEN` | 时间戳过旧 / 在未来 |
| `SERVER_NOT_CONFIGURED` | 缺少包名或证书白名单配置 |
| `SERVER_CREDENTIAL_ERROR` | 服务账号不可用 |
| `DECODE_FAILED` / `UPSTREAM_UNREACHABLE` | 调用 Google 解码接口失败 |

## 6. 部署建议

- 用 HTTPS（反代 Caddy / Nginx 或平台托管），明文 HTTP 会让 token 在链路上裸奔。
- 至少加一层鉴权（固定 token / mTLS / IP 白名单），否则任何人都能拿你的配额刷解码。
- 凭据只通过环境变量或密钥管理服务注入；不要把 JSON 放进镜像层。
- 生产环境建议把 nonce 记录在短 TTL 的存储里并做**一次性消费**，可进一步防重放。

## 7. 已知限制

- **App 必须能从 Play 服务拿到 token**：没有 GMS 的设备（大多数国内 ROM）会直接失败，这不是服务端问题。
- `appRecognitionVerdict` 在侧载安装时通常是 `UNRECOGNIZED` / `UNRECOGNIZED_VERSION`，这是正常的，
  不代表设备完整性失败；只有 `deviceRecognitionVerdict` 才反映设备状态。
- 判定结果依赖 Google 的实时判定，任何"缓存判定"都可能过期，故 App 不做本地缓存。
