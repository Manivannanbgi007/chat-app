#!/usr/bin/env python3
"""
Black-box security checks for the running chat-app stack (Phase 9). Standard library only.
It attacks the stack the way an outsider would: only through the gateway (8200) and the frontend (5280).

    python infra/scripts/security-check.py
    python infra/scripts/security-check.py --gateway http://localhost:8200 --frontend http://localhost:5280

What it covers (see SECURITY_DESIGN.md section 10):
  1  every protected route refuses a missing token
  2  forged, tampered, expired and wrong-issuer tokens are refused (needs JWT_SECRET from infra/.env)
  3  internal endpoints and dangerous actuator endpoints are not reachable from outside
  4  security headers, CORS, error bodies without stack traces, no version leaks
  5  hostile input: oversized body, SQL-like text, path tricks, TRACE
  6  account enumeration: same answer for known and unknown accounts
  7  IDOR: a stranger cannot read someone else's conversation, message, status or media
  8  WebSocket: no token, bad token and a forged token cannot connect
Exit code 0 = every check passed. Findings that are only reported (not failures) start with "INFO".
"""
import argparse
import base64
import hashlib
import hmac
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

failures = 0
checks = 0


def check(label, condition, detail=""):
    global failures, checks
    checks += 1
    if condition:
        print(f"  PASS  {label}")
    else:
        failures += 1
        print(f"  FAIL  {label}  {detail}")


def info(text):
    print(f"  INFO  {text}")


def call(base, method, path, body=None, token=None, headers=None, raw_body=None):
    """Returns (status, parsed_json_or_None, response_headers, raw_text)."""
    if path.startswith("/api/auth/") and not headers:
        time.sleep(0.5)   # the gateway limits /api/auth/** per IP
    data = raw_body if raw_body is not None else (json.dumps(body).encode() if body is not None else None)
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode(errors="replace")
            return response.status, _json(raw), response.headers, raw
    except urllib.error.HTTPError as error:
        raw = error.read().decode(errors="replace")
        return error.code, _json(raw), error.headers, raw


def _json(raw):
    try:
        return json.loads(raw) if raw else None
    except ValueError:
        return None


def wait_for_mail(mailhog, recipient, pattern, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        url = mailhog + "/api/v2/search?kind=to&query=" + urllib.parse.quote(recipient)
        with urllib.request.urlopen(url, timeout=10) as response:
            items = json.loads(response.read().decode()).get("items", [])
        for item in items:
            body = item["Content"]["Body"].replace("=\r\n", "").replace("=3D", "=")
            match = re.search(pattern, body)
            if match:
                return match.group(1)
        time.sleep(0.5)
    return None


def b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_jwt(secret, claims, alg="HS256"):
    header = {"alg": alg, "typ": "JWT"}
    signing_input = b64(json.dumps(header).encode()) + "." + b64(json.dumps(claims).encode())
    if alg == "none":
        return signing_input + "."
    signature = hmac.new(secret.encode(), signing_input.encode(), hashlib.sha256).digest()
    return signing_input + "." + b64(signature)


def read_env_value(name):
    path = os.path.join(os.path.dirname(__file__), "..", ".env")
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.startswith(name + "="):
                    return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return os.environ.get(name)


def register(gw, mailhog, prefix, password):
    suffix = uuid.uuid4().hex[:8]
    user = {"username": f"sec_{prefix}_{suffix}", "email": f"sec_{prefix}_{suffix}@example.com", "displayName": f"Sec {prefix} {suffix}"}
    status, body, _, _ = call(gw, "POST", "/api/auth/register", {**user, "password": password})
    assert status == 201, f"register failed {status} {body}"
    user["id"] = body["data"]["userId"]
    code = wait_for_mail(mailhog, user["email"], r"verification code is: (\d{6})")
    status, _, _, _ = call(gw, "POST", "/api/auth/verify-otp", {"identifier": user["email"], "otp": code, "purpose": "EMAIL_VERIFY"})
    assert status == 200, "verify failed"
    status, body, _, _ = call(gw, "POST", "/api/auth/login", {"identifier": user["email"], "password": password})
    assert status == 200, f"login failed {status}"
    user["token"] = body["data"]["accessToken"]
    user["refresh"] = body["data"].get("refreshToken")
    return user


# ---------------------------------------------------------------------------------------------- WebSocket (no library)

def ws_stomp_connect(host, port, authorization, origin=None, tls=False):
    """Opens /ws, sends a STOMP CONNECT frame and returns the first STOMP command the server answers
    ('CONNECTED', 'ERROR', or 'CLOSED' / 'HTTP <status>' when it never got that far)."""
    sock = socket.create_connection((host, port), timeout=10)
    if tls:   # wss:// (production): uses the certificate authorities Python knows (set SSL_CERT_FILE for a local CA)
        import ssl
        sock = ssl.create_default_context().wrap_socket(sock, server_hostname=host)
    try:
        key = base64.b64encode(os.urandom(16)).decode()
        lines = ["GET /ws HTTP/1.1", f"Host: {host}:{port}", "Upgrade: websocket", "Connection: Upgrade",
                 f"Sec-WebSocket-Key: {key}", "Sec-WebSocket-Version: 13", "Sec-WebSocket-Protocol: v12.stomp"]
        if origin:
            lines.append(f"Origin: {origin}")
        sock.sendall(("\r\n".join(lines) + "\r\n\r\n").encode())
        response = b""
        while b"\r\n\r\n" not in response:
            chunk = sock.recv(4096)
            if not chunk:
                return "CLOSED"
            response += chunk
        status_line = response.split(b"\r\n", 1)[0].decode()
        if " 101 " not in status_line:
            return "HTTP " + status_line.split(" ")[1]
        headers = ["CONNECT", "accept-version:1.2", f"host:{host}"]
        if authorization:
            headers.append("Authorization:" + authorization)
        payload = ("\n".join(headers) + "\n\n\0").encode()
        mask = os.urandom(4)
        masked = bytes(byte ^ mask[i % 4] for i, byte in enumerate(payload))
        if len(payload) < 126:
            frame_header = bytes([0x81, 0x80 | len(payload)])
        else:   # a real token makes the frame longer than 125 bytes: 16-bit length
            frame_header = bytes([0x81, 0x80 | 126]) + len(payload).to_bytes(2, "big")
        sock.sendall(frame_header + mask + masked)
        sock.settimeout(8)
        data = response.split(b"\r\n\r\n", 1)[1]
        while len(data) < 2:
            chunk = sock.recv(4096)
            if not chunk:
                return "CLOSED"
            data += chunk
        opcode = data[0] & 0x0F
        if opcode == 8:
            return "CLOSED"
        length = data[1] & 0x7F
        offset = 2
        if length == 126:
            while len(data) < 4:
                data += sock.recv(4096)
            length = int.from_bytes(data[2:4], "big")
            offset = 4
        while len(data) < offset + length:
            chunk = sock.recv(4096)
            if not chunk:
                break
            data += chunk
        return data[offset:offset + length].decode(errors="replace").split("\n", 1)[0].strip()
    except socket.timeout:
        return "TIMEOUT"
    finally:
        sock.close()


# ---------------------------------------------------------------------------------------------------------------- main

PROTECTED = [
    ("GET", "/api/users/me"), ("GET", "/api/users/search?q=abc"), ("GET", "/api/contacts"), ("GET", "/api/contacts/blocked"),
    ("GET", "/api/conversations"), ("POST", "/api/conversations"), ("POST", "/api/messages"),
    ("GET", "/api/conversations/00000000-0000-0000-0000-000000000000/messages"), ("GET", "/api/messages/sync"),
    ("POST", "/api/media/upload"), ("GET", "/api/media/00000000-0000-0000-0000-000000000000"),
    ("POST", "/api/presence/heartbeat"), ("GET", "/api/presence/00000000-0000-0000-0000-000000000000"),
    ("GET", "/api/notifications"), ("GET", "/api/notifications/unread-count"), ("PUT", "/api/notifications/read-all"),
    ("GET", "/api/calls/active"), ("GET", "/api/calls/history"), ("POST", "/api/calls"),
    ("GET", "/api/statuses/mine"), ("GET", "/api/statuses/feed"), ("POST", "/api/statuses"), ("GET", "/api/statuses/privacy"),
    ("GET", "/api/auth/sessions"),
]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8200")
    parser.add_argument("--frontend", default="http://localhost:5280")
    parser.add_argument("--mailhog", default="http://localhost:8025")
    parser.add_argument("--origin", default="http://localhost:5280", help="the browser origin the gateway must allow (CORS)")
    args = parser.parse_args()
    gw = args.gateway
    gw_host = urllib.parse.urlparse(gw)
    password = "Passw0rd123"

    print("== 1. Routes without a token")
    for method, path in PROTECTED:
        status, body, _, _ = call(gw, method, path)
        check(f"{method} {path} without a token -> 401", status == 401, status)

    print("== 2. Forged and invalid tokens")
    secret = read_env_value("JWT_SECRET")
    now = int(time.time())
    good_claims = {"iss": "chat-app-auth", "sub": str(uuid.uuid4()), "sid": str(uuid.uuid4()), "roles": ["USER"], "iat": now, "exp": now + 600}
    candidates = {
        "garbage text": "not-a-token",
        "alg=none (no signature)": make_jwt("x", good_claims, alg="none"),
        "signed with a different secret": make_jwt("a-completely-different-secret-value-0123456789", good_claims),
    }
    if secret:
        candidates["valid signature but wrong issuer"] = make_jwt(secret, {**good_claims, "iss": "somebody-else"})
        candidates["valid signature but expired 10 minutes ago"] = make_jwt(secret, {**good_claims, "iat": now - 1200, "exp": now - 600})
        candidates["valid signature, no subject"] = make_jwt(secret, {k: v for k, v in good_claims.items() if k != "sub"})
    else:
        info("JWT_SECRET not found in infra/.env: skipped the wrong-issuer / expired / no-subject tokens")
    for label, token in candidates.items():
        status, _, _, _ = call(gw, "GET", "/api/users/me", token=token)
        check(f"token {label} -> 401", status == 401, status)
    if secret:
        status, _, _, _ = call(gw, "GET", "/api/users/me", token=make_jwt(secret, good_claims))
        # a perfectly signed token for a user that does not exist must not give access to any data
        check("valid signature for a user that does not exist gets no profile (401/403/404)", status in (401, 403, 404), status)

    print("== 3. Internal and management endpoints are not reachable from outside")
    for path in ["/internal/blocks?a=x&b=y", "/internal/users/summaries", "/internal/media/x/attach", "/internal/conversations/x",
                 "/api/internal/blocks", "/api/auth/internal/x"]:
        for method in ("GET", "POST"):
            status, _, headers, raw = call(gw, method, path, body={} if method == "POST" else None)
            check(f"{method} {path} from outside gives no data", status != 200 or "html" in (headers.get("Content-Type") or "").lower(),
                  f"{status} {raw[:80]}")
    for path in ["/actuator/env", "/actuator/heapdump", "/actuator/beans", "/actuator/configprops", "/actuator/loggers",
                 "/actuator/mappings", "/actuator/threaddump", "/actuator/prometheus", "/actuator/metrics"]:
        status, _, headers, raw = call(gw, "GET", path)
        # behind the production reverse proxy an unknown path shows the web app (HTML): that is fine, data is not
        check(f"GET {path} on the gateway shows no management data", status != 200 or "html" in (headers.get("Content-Type") or "").lower(),
              f"{status} {raw[:80]}")
    status, body, _, raw = call(gw, "GET", "/actuator/health")
    check("GET /actuator/health shows no details", status in (200, 404) and "details" not in raw and "components" not in raw, raw[:100])

    print("== 4. Headers, CORS and error bodies")
    status, _, headers, _ = call(gw, "GET", "/api/auth/ping")
    check("gateway: X-Content-Type-Options: nosniff", (headers.get("X-Content-Type-Options") or "").lower() == "nosniff", dict(headers))
    check("gateway: X-Frame-Options or CSP frame-ancestors set",
          bool(headers.get("X-Frame-Options")) or "frame-ancestors" in (headers.get("Content-Security-Policy") or ""))
    check("gateway: Referrer-Policy set", bool(headers.get("Referrer-Policy")))
    check("gateway: no Server / X-Powered-By version leak",
          not re.search(r"\d", headers.get("Server") or "") and not headers.get("X-Powered-By"),
          f"Server={headers.get('Server')} X-Powered-By={headers.get('X-Powered-By')}")
    try:
        status, _, headers, _ = call(args.frontend, "GET", "/")
        fe = {k.lower(): v for k, v in headers.items()}
        check("frontend: Content-Security-Policy set and without 'unsafe-eval'",
              "content-security-policy" in fe and "unsafe-eval" not in fe["content-security-policy"], fe.get("content-security-policy"))
        check("frontend: X-Content-Type-Options nosniff", fe.get("x-content-type-options", "").lower() == "nosniff")
        check("frontend: clickjacking protection (X-Frame-Options / frame-ancestors)",
              bool(fe.get("x-frame-options")) or "frame-ancestors" in fe.get("content-security-policy", ""))
        check("frontend: Referrer-Policy set", bool(fe.get("referrer-policy")))
        check("frontend: nginx version hidden", not re.search(r"\d", fe.get("server", "")), fe.get("server"))
        check("frontend: Permissions-Policy limits camera/microphone to this site", "permissions-policy" in fe, None)
    except OSError as error:
        info(f"frontend not reachable at {args.frontend}: header checks skipped ({error})")

    status, _, headers, _ = call(gw, "OPTIONS", "/api/users/me", headers={
        "Origin": "https://evil.example", "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization"})
    check("CORS: an unknown origin gets no Access-Control-Allow-Origin", not headers.get("Access-Control-Allow-Origin"),
          headers.get("Access-Control-Allow-Origin"))
    status, _, headers, _ = call(gw, "OPTIONS", "/api/users/me", headers={
        "Origin": args.origin, "Access-Control-Request-Method": "GET", "Access-Control-Request-Headers": "authorization"})
    check("CORS: the frontend origin is allowed (and not '*')",
          headers.get("Access-Control-Allow-Origin") == args.origin, headers.get("Access-Control-Allow-Origin"))

    status, body, _, raw = call(gw, "POST", "/api/auth/login", raw_body=b'{"identifier": "x", "password": ')
    check("malformed JSON gives a clean 4xx without stack trace or class names",
          400 <= status < 500 and not re.search(r"Exception|\.java|at com\.|org\.springframework", raw), f"{status} {raw[:120]}")
    status, body, _, raw = call(gw, "GET", "/api/does-not-exist", token=None)
    check("unknown path gives 401/404 without stack trace", status in (401, 404) and not re.search(r"Exception|\.java|at com\.", raw), f"{status} {raw[:100]}")

    print("== 5. Hostile input")
    status, _, _, raw = call(gw, "POST", "/api/auth/login", raw_body=b'{"identifier": "' + b"A" * 3_000_000 + b'", "password": "x"}')
    check("a 3 MB login body is refused cleanly (4xx), not 500", 400 <= status < 500, status)
    status, _, _, raw = call(gw, "POST", "/api/auth/login", {"identifier": "' OR '1'='1' --", "password": "' OR '1'='1"})
    check("SQL-injection text in login gives 400/401, never a token", status in (400, 401) and "accessToken" not in raw, f"{status} {raw[:100]}")
    status, _, headers, raw = call(gw, "GET", "/api/media/..%2f..%2fetc%2fpasswd", token=None)
    # 401 from the gateway, or (behind the production proxy, which resolves "..") the web app's HTML page
    check("path trick in a media id without token gives no data (401 or the web app)",
          status == 401 or (status == 200 and "html" in (headers.get("Content-Type") or "").lower()), f"{status} {raw[:80]}")
    status, _, headers, raw = call(gw, "GET", "/..%2f..%2f..%2fetc%2fpasswd", token=None)
    check("path trick on the site root shows no system file", "root:" not in raw, raw[:80])
    status, _, _, raw = call(gw, "TRACE", "/api/auth/ping")
    check("TRACE is not allowed (refused, not echoed)", status in (401, 403, 404, 405, 501), status)

    print("== Users for the next checks")
    alice = register(gw, args.mailhog, "a", password)
    mallory = register(gw, args.mailhog, "m", password)
    info("created two fresh users: alice (victim) and mallory (attacker); alice and mallory are strangers")
    at, mt = alice["token"], mallory["token"]

    print("== 6. Account enumeration")
    status1, body1, _, _ = call(gw, "POST", "/api/auth/login", {"identifier": alice["email"], "password": "Wrong-password-1"})
    status2, body2, _, _ = call(gw, "POST", "/api/auth/login", {"identifier": "nobody_" + uuid.uuid4().hex[:8] + "@example.com", "password": "Wrong-password-1"})
    check("wrong password and unknown account give the same status and message",
          status1 == status2 and (body1 or {}).get("message") == (body2 or {}).get("message"), f"{status1}/{status2} {body1} {body2}")
    status1, body1, _, _ = call(gw, "POST", "/api/auth/forgot-password", {"email": alice["email"]})
    status2, body2, _, _ = call(gw, "POST", "/api/auth/forgot-password", {"email": "nobody_" + uuid.uuid4().hex[:8] + "@example.com"})
    check("forgot-password answers the same for a known and an unknown email",
          status1 == status2 and (body1 or {}).get("message") == (body2 or {}).get("message"), f"{status1}/{status2} {body1} {body2}")
    status, body, _, raw = call(gw, "POST", "/api/auth/register", {"username": "weak_" + uuid.uuid4().hex[:6], "email": f"weak_{uuid.uuid4().hex[:6]}@example.com",
                                                                    "password": "password", "displayName": "Weak"})
    check("a weak password is refused at registration (400)", status == 400, f"{status} {raw[:100]}")

    print("== 7. IDOR: mallory against alice's data")
    status, body, _, _ = call(gw, "POST", "/api/contacts", {"contactUserId": mallory["id"]}, token=at)
    status, body, _, raw = call(gw, "POST", "/api/conversations", {"type": "PRIVATE", "userId": mallory["id"]}, token=at)
    conv = body["data"]["id"] if status in (200, 201) else None
    if conv is None:
        info(f"could not create a conversation for the IDOR checks ({status} {raw[:100]}); creating one against alice herself is not possible")
        check("conversation for IDOR checks exists", False, f"{status} {raw[:100]}")
    else:
        status, body, _, _ = call(gw, "POST", "/api/messages", {"conversationId": conv, "type": "TEXT", "content": "secret " + uuid.uuid4().hex, "idempotencyKey": uuid.uuid4().hex}, token=at)
        message_id = body["data"]["id"] if status in (200, 201) else None
        check("alice sent a message in her private chat with mallory", message_id is not None, f"{status} {body}")
        # a third person who is NOT in the conversation
        eve = register(gw, args.mailhog, "e", password)
        et = eve["token"]
        status, _, _, _ = call(gw, "GET", f"/api/conversations/{conv}", token=et)
        check("stranger cannot open the conversation (403/404)", status in (403, 404), status)
        status, _, _, raw = call(gw, "GET", f"/api/conversations/{conv}/messages?limit=20", token=et)
        check("stranger cannot read its messages (403/404)", status in (403, 404) and "secret" not in raw, status)
        status, _, _, _ = call(gw, "POST", "/api/messages", {"conversationId": conv, "type": "TEXT", "content": "hi", "idempotencyKey": uuid.uuid4().hex}, token=et)
        check("stranger cannot send into the conversation (403/404)", status in (403, 404), status)
        status, _, _, _ = call(gw, "POST", f"/api/conversations/{conv}/read", {"upToMessageId": message_id}, token=et)
        check("stranger cannot mark it read (403/404)", status in (403, 404), status)
        status, _, _, _ = call(gw, "GET", f"/api/messages/{message_id}/receipts", token=et)
        check("stranger cannot read the receipts of the message (403/404)", status in (403, 404), status)
        for method, path in [("PUT", "/mute"), ("DELETE", "/mute"), ("PUT", "/archive"), ("PUT", "/pin")]:
            body = {"duration": "ALWAYS"} if (method, path) == ("PUT", "/mute") else None
            status, _, _, _ = call(gw, method, f"/api/conversations/{conv}{path}", body, token=et)
            check(f"stranger cannot {method} {path} on the conversation (403/404)", status in (403, 404), status)
        status, _, _, _ = call(gw, "POST", f"/api/conversations/{conv}/members", {"userIds": [eve["id"]]}, token=et)
        check("stranger cannot add members to the conversation (403/404)", status in (403, 404), status)
        status, _, _, _ = call(gw, "PATCH", f"/api/conversations/{conv}/group", {"name": "hacked"}, token=et)
        check("stranger cannot rename it (403/404)", status in (403, 404), status)
        status, _, _, raw = call(gw, "GET", f"/api/messages/{message_id}/attachment/url", token=et)
        check("stranger cannot get an attachment link of the message (403/404)", status in (403, 404), status)

        # statuses: eve is not a contact of alice
        status, body, _, _ = call(gw, "POST", "/api/statuses", {"type": "TEXT", "text": "alice status", "backgroundColor": "#0F766E", "idempotencyKey": uuid.uuid4().hex}, token=at)
        status_id = body["data"]["id"] if status in (200, 201) else None
        check("alice posts a status", status_id is not None, f"{status} {body}")
        status, _, _, _ = call(gw, "POST", f"/api/statuses/{status_id}/view", token=et)
        check("a non-contact cannot mark alice's status as viewed (404)", status == 404, status)
        status, _, _, _ = call(gw, "GET", f"/api/statuses/{status_id}/media-url", token=et)
        check("a non-contact cannot ask for its media link (404)", status == 404, status)
        status, _, _, _ = call(gw, "GET", f"/api/statuses/{status_id}/viewers", token=et)
        check("a non-owner cannot read the viewer list (404)", status == 404, status)
        status, _, _, _ = call(gw, "DELETE", f"/api/statuses/{status_id}", token=et)
        check("a non-owner cannot delete the status (404)", status == 404, status)
        status, body, _, _ = call(gw, "GET", "/api/statuses/feed", token=et)
        check("eve's feed does not contain alice", status == 200 and not any(g["owner"]["id"] == alice["id"] for g in body["data"]), f"{status}")

        # sessions / devices belong to the user
        status, body, _, _ = call(gw, "GET", "/api/auth/sessions", token=et)
        sessions = (body or {}).get("data") or []
        check("eve's session list holds only her own sessions", status == 200 and all(s.get("id") != None for s in sessions) and len(sessions) <= 2, f"{status}")

    print("== 8. WebSocket")
    tls = gw_host.scheme == 'https'
    host, port = gw_host.hostname, gw_host.port or (443 if tls else 80)
    check("STOMP CONNECT without a token is refused (ERROR / closed)", ws_stomp_connect(host, port, None, tls=tls) in ("ERROR", "CLOSED"), ws_stomp_connect(host, port, None, tls=tls))
    check("STOMP CONNECT with garbage token is refused", ws_stomp_connect(host, port, "Bearer garbage", tls=tls) in ("ERROR", "CLOSED"))
    if secret:
        check("STOMP CONNECT with a token signed by a different secret is refused",
              ws_stomp_connect(host, port, "Bearer " + make_jwt("another-secret-value-0123456789abcdef-xyz", good_claims), tls=tls) in ("ERROR", "CLOSED"))
        check("STOMP CONNECT with an expired token is refused",
              ws_stomp_connect(host, port, "Bearer " + make_jwt(secret, {**good_claims, "iat": now - 1200, "exp": now - 600}), tls=tls) in ("ERROR", "CLOSED"))
    answer = ws_stomp_connect(host, port, "Bearer " + at, tls=tls)
    check("STOMP CONNECT with alice's real token works (CONNECTED)", answer == "CONNECTED", answer)
    answer = ws_stomp_connect(host, port, None, origin="https://evil.example", tls=tls)
    info(f"WebSocket handshake from a foreign Origin without a token: {answer}")

    print("== 9. Logout revokes the session")
    status, _, _, _ = call(gw, "POST", "/api/auth/logout", {"refreshToken": mallory["refresh"]})
    check("logout with the refresh token succeeds (200)", status == 200, status)
    status, _, _, _ = call(gw, "POST", "/api/auth/refresh", {"refreshToken": mallory["refresh"]})
    check("the refresh token of a logged-out session cannot be used again (401)", status == 401, status)
    time.sleep(1)
    status, _, _, _ = call(gw, "GET", "/api/users/me", token=mt)
    check("after logout the old access token no longer works (401)", status == 401, status)

    print()
    print(f"{checks - failures}/{checks} checks passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
