"""
Play Integrity 解码服务（最小可运行示例）
=========================================

职责：
  1. 接收 App 发来的加密 integrity token 与本次使用的 nonce；
  2. 用服务账号调用 Google 的 playintegrity.googleapis.com 解码；
  3. 校验 nonce / 包名 / 签名证书摘要 / 时间戳新鲜度；
  4. 把 verdict 原样返回给 App。

设计原则：
  * 客户端永远不该拿到可解的 token —— 解密只在这里做。
  * 任何一项校验失败都返回 ok=false，绝不返回「通过」。
  * 凭据只从环境变量指向的文件读取，不写进代码、不进仓库。

运行：
  pip install -r requirements.txt
  export GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json
  export INTEGRITY_PACKAGE_NAME=dev.mochi.rootbox
  export INTEGRITY_CERT_SHA256=<签名证书 SHA-256，逗号分隔可多个>
  python server.py
"""

from __future__ import annotations

import os
import time
from typing import Any

import requests
from flask import Flask, jsonify, request
from google.auth.transport.requests import Request as GoogleAuthRequest
from google.oauth2 import service_account

SCOPE = "https://www.googleapis.com/auth/playintegrity"
DECODE_URL = "https://playintegrity.googleapis.com/v1/{package}:decodeIntegrityToken"

# 允许的时间偏移：token 从生成到送达这里，通常几秒内。
MAX_AGE_SECONDS = int(os.environ.get("INTEGRITY_MAX_AGE_SECONDS", "120"))

app = Flask(__name__)

_credentials = None
_access_token: str | None = None
_access_token_expiry = 0.0


def expected_package() -> str:
    value = os.environ.get("INTEGRITY_PACKAGE_NAME", "").strip()
    if not value:
        raise RuntimeError("INTEGRITY_PACKAGE_NAME 未设置")
    return value


def expected_cert_digests() -> set[str]:
    raw = os.environ.get("INTEGRITY_CERT_SHA256", "")
    return {item.strip().lower() for item in raw.split(",") if item.strip()}


def get_access_token() -> str:
    """服务账号换取 OAuth token，并做 60 秒提前刷新。"""
    global _credentials, _access_token, _access_token_expiry

    path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip()
    if not path:
        raise RuntimeError("GOOGLE_APPLICATION_CREDENTIALS 未设置（指向服务账号 JSON）")
    if not os.path.isfile(path):
        raise RuntimeError(f"服务账号文件不存在：{path}")

    if _credentials is None:
        _credentials = service_account.Credentials.from_service_account_file(path, scopes=[SCOPE])

    now = time.time()
    if _access_token and now < _access_token_expiry - 60:
        return _access_token

    if not _credentials.valid:
        _credentials.refresh(GoogleAuthRequest())
    _access_token = _credentials.token
    _access_token_expiry = _credentials.expiry.timestamp() if _credentials.expiry else now + 3000
    return _access_token


def fail(code: str, message: str, status: int = 400):
    # 校验失败时绝不附带任何 verdict 字段。
    return jsonify({"ok": False, "code": code, "error": message}), status


@app.post("/integrity")
def integrity() -> Any:
    body = request.get_json(silent=True) or {}
    token = (body.get("token") or "").strip()
    nonce = (body.get("nonce") or "").strip()

    if not token:
        return fail("MISSING_TOKEN", "请求缺少 token")
    if not nonce:
        return fail("MISSING_NONCE", "请求缺少 nonce")

    try:
        package = expected_package()
    except RuntimeError as error:
        return fail("SERVER_NOT_CONFIGURED", str(error), 500)

    allowed_certs = expected_cert_digests()
    if not allowed_certs:
        return fail("SERVER_NOT_CONFIGURED", "未配置 INTEGRITY_CERT_SHA256，无法校验签名证书", 500)

    try:
        access_token = get_access_token()
    except Exception as error:  # noqa: BLE001 - 配置问题需要原样告诉部署者
        app.logger.error("获取服务账号 token 失败: %s", error)
        return fail("SERVER_CREDENTIAL_ERROR", f"服务账号不可用：{error}", 500)

    try:
        response = requests.post(
            DECODE_URL.format(package=package),
            headers={
                "Authorization": f"Bearer {access_token}",
                "Content-Type": "application/json",
            },
            json={"integrity_token": token},
            timeout=15,
        )
    except requests.RequestException as error:
        app.logger.error("调用 decodeIntegrityToken 失败: %s", error)
        return fail("UPSTREAM_UNREACHABLE", f"无法连接 Google 解码接口：{error}", 502)

    if response.status_code != 200:
        app.logger.error("decodeIntegrityToken HTTP %s: %s", response.status_code, response.text[:500])
        return fail(
            "DECODE_FAILED",
            f"Google 返回 HTTP {response.status_code}：{response.text[:300]}",
            502,
        )

    payload = response.json().get("tokenPayloadExternal") or {}
    request_details = payload.get("requestDetails") or {}
    app_integrity = payload.get("appIntegrity") or {}
    device_integrity = payload.get("deviceIntegrity") or {}
    account_details = payload.get("accountDetails") or {}

    # ---- 校验 1：nonce 必须与本次请求一致（防重放）
    if request_details.get("nonce") != nonce:
        return fail("NONCE_MISMATCH", "token 内的 nonce 与请求不一致")

    # ---- 校验 2：包名必须是本应用
    if request_details.get("requestPackageName") != package:
        return fail(
            "PACKAGE_MISMATCH",
            f"token 包名 {request_details.get('requestPackageName')!r} 与期望的 {package!r} 不一致",
        )
    if app_integrity.get("packageName") and app_integrity.get("packageName") != package:
        return fail("PACKAGE_MISMATCH", "应用完整性字段里的包名不一致")

    # ---- 校验 3：签名证书摘要必须在白名单内
    digests = {str(item).lower() for item in (app_integrity.get("certificateSha256Digest") or [])}
    if not digests & allowed_certs:
        return fail("CERT_MISMATCH", "签名证书摘要与白名单不匹配")

    # ---- 校验 4：时间戳新鲜度
    timestamp_ms = int(request_details.get("timestampMillis") or 0)
    if timestamp_ms <= 0:
        return fail("MISSING_TIMESTAMP", "token 缺少 timestampMillis")
    age = time.time() - timestamp_ms / 1000.0
    if age > MAX_AGE_SECONDS:
        return fail("STALE_TOKEN", f"token 已过期（{int(age)}s > {MAX_AGE_SECONDS}s）")
    if age < -60:
        return fail("FUTURE_TOKEN", "token 时间戳在未来，本地时钟可能不正确")

    device_verdicts = device_integrity.get("deviceRecognitionVerdict") or []

    return jsonify(
        {
            "ok": True,
            "verdict": {
                "requestPackageName": request_details.get("requestPackageName"),
                "appRecognitionVerdict": app_integrity.get("appRecognitionVerdict"),
                "deviceRecognitionVerdict": device_verdicts,
                "accountLicensingVerdict": account_details.get("appLicensingVerdict"),
                "timestampMillis": timestamp_ms,
                "certificateSha256Digest": list(digests),
                "versionCode": app_integrity.get("versionCode"),
            },
            # 派生字段只由上面已验证过的标签推导，方便客户端直接展示。
            "derived": {
                "meetsBasicIntegrity": "MEETS_BASIC_INTEGRITY" in device_verdicts,
                "meetsDeviceIntegrity": "MEETS_DEVICE_INTEGRITY" in device_verdicts,
                "meetsStrongIntegrity": "MEETS_STRONG_INTEGRITY" in device_verdicts,
            },
            "raw": payload,
        }
    )


@app.get("/healthz")
def healthz() -> Any:
    return jsonify({"ok": True, "package": os.environ.get("INTEGRITY_PACKAGE_NAME", "")})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    app.run(host="0.0.0.0", port=port)
