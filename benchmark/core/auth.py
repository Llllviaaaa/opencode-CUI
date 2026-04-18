"""认证工具 — AK/SK 签名、SS internal token、MiniApp Cookie。"""

import base64
import hashlib
import hmac
import json
import time
import uuid


def build_agent_auth_protocol(ak: str, sk: str) -> str:
    ts = str(int(time.time() * 1000))
    nonce = uuid.uuid4().hex
    sign_str = ak + ts + nonce
    sign = hmac.new(sk.encode(), sign_str.encode(), hashlib.sha256).hexdigest()
    payload = json.dumps({"ak": ak, "ts": ts, "nonce": nonce, "sign": sign})
    return f"auth.{base64.b64encode(payload.encode()).decode()}"


def build_skill_auth_protocol(
    token: str, source: str, instance_id: str
) -> str:
    payload = json.dumps(
        {"token": token, "source": source, "instanceId": instance_id}
    )
    return f"auth.{base64.b64encode(payload.encode()).decode()}"


def build_miniapp_headers(user_id: str) -> dict:
    return {"Cookie": f"userId={user_id}"}
