#!/usr/bin/env python3
"""
End-to-end smoke test for the running chat-app stack (Docker Compose or IDE mode).
It talks only to the API gateway, like the frontend does, and reads verification codes
and reset links from MailHog. Uses only the Python standard library.

    python infra/scripts/smoke-test.py
    python infra/scripts/smoke-test.py --gateway http://localhost:8200 --mailhog http://localhost:8025

Each run creates three new users with random names (Phase 4 adds group checks). Exit code 0 = all checks passed.
"""
import argparse
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

# Rehearsal of the production setup on your own computer: files are served from https://files.localhost, a name that
# Python (unlike Chrome) does not resolve. SMOKE_RESOLVE=files.localhost=127.0.0.1 maps it. Not needed otherwise.
_resolve_map = dict(item.split("=", 1) for item in os.environ.get("SMOKE_RESOLVE", "").split(",") if "=" in item)
if _resolve_map:
    _real_getaddrinfo = socket.getaddrinfo
    socket.getaddrinfo = lambda host, *rest, **kw: _real_getaddrinfo(_resolve_map.get(host, host), *rest, **kw)

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


def call(base, method, path, body=None, token=None, headers=None):
    """Returns (status, parsed_json_or_None, response_headers)."""
    if path.startswith("/api/auth/") and not headers:
        time.sleep(0.5)   # stay under the gateway's login rate limit (about 2 requests per second per IP)
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(base + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", "Bearer " + token)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode()
            return response.status, (json.loads(raw) if raw else None), response.headers
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            return error.code, (json.loads(raw) if raw else None), error.headers
        except ValueError:
            return error.code, None, error.headers


def wait_for_mail(mailhog, recipient, pattern, timeout=15):
    """Polls MailHog until a mail to `recipient` contains `pattern`; returns the first regex group."""
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


def wait_for(fn, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = fn()
        if result:
            return result
        time.sleep(0.5)
    return None


def run_group_checks(gw, alice, bob, carol, at, bt, ct, private_conv):
    """Phase 4: groups, admins, the removed-member rule, group receipts, SYSTEM messages, mute / archive / pin.
    At this point bob's privacy says groupAdd = CONTACTS and nobody has anybody as a contact."""
    print("== Groups")

    def new_key():
        return uuid.uuid4().hex

    def send(token, conversation_id, text):
        return call(gw, "POST", "/api/messages",
                    {"conversationId": conversation_id, "type": "TEXT", "content": text, "idempotencyKey": new_key()}, token=token)

    def history_ids(token, conversation_id):
        s, b, _ = call(gw, "GET", f"/api/conversations/{conversation_id}/messages?limit=100", token=token)
        return [m["id"] for m in b["data"]["items"]] if s == 200 else None

    def system_events(token, conversation_id):
        s, b, _ = call(gw, "GET", f"/api/conversations/{conversation_id}/messages?limit=100", token=token)
        if s != 200:
            return []
        return [json.loads(m["content"])["event"] for m in b["data"]["items"] if m["type"] == "SYSTEM"]

    def status_for_sender(token, conversation_id, message_id):
        s, b, _ = call(gw, "GET", f"/api/conversations/{conversation_id}/messages?limit=100", token=token)
        return next((m["status"] for m in b["data"]["items"] if m["id"] == message_id), None) if s == 200 else None

    members = [bob["id"], carol["id"]]
    status, body, _ = call(gw, "POST", "/api/conversations",
                           {"type": "GROUP", "name": "Smoke group", "memberIds": members}, token=at)
    check("bob's privacy (groupAdd CONTACTS) refuses alice: 403 GROUP_ADD_NOT_ALLOWED naming bob only",
          status == 403 and body["data"]["errorCode"] == "GROUP_ADD_NOT_ALLOWED" and body["data"]["userIds"] == [bob["id"]],
          f"{status} {body}")

    status, body, _ = call(gw, "POST", "/api/contacts", {"contactUserId": alice["id"]}, token=bt)
    check("bob adds alice as a contact", status == 201, f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/conversations",
                           {"type": "GROUP", "name": "  Smoke group  ", "description": "Testing", "memberIds": members + [alice["id"]]},
                           token=at)
    check("now alice can create the group (201, she is ADMIN, 3 members, name trimmed)",
          status == 201 and body["data"]["type"] == "GROUP" and body["data"]["membership"]["role"] == "ADMIN"
          and len(body["data"]["participants"]) == 3 and body["data"]["group"]["name"] == "Smoke group"
          and body["data"]["otherUser"] is None, f"{status} {body}")
    group = body["data"]["id"] if body else None

    status, body, _ = call(gw, "GET", f"/api/conversations/{group}", token=bt)
    check("bob sees the group as MEMBER with alice as the only admin",
          status == 200 and body["data"]["membership"]["role"] == "MEMBER" and body["data"]["group"]["adminIds"] == [alice["id"]],
          f"{status} {body}")
    check("the group is in carol's chat list", any(c["id"] == group for c in (call(gw, "GET", "/api/conversations", token=ct)[1] or {"data": []})["data"]))
    check("a GROUP_CREATED system message appears (conversation.events -> message-service)",
          wait_for(lambda: "GROUP_CREATED" in system_events(ct, group)) is True)

    status, body, _ = send(at, group, "Hello group")
    g1 = body["data"]["id"] if status == 201 else None
    check("alice sends to the group", g1 is not None, f"{status} {body}")
    check("bob and carol both see it", g1 in (history_ids(bt, group) or []) and g1 in (history_ids(ct, group) or []))
    status, body, _ = send(bt, group, "Hi from bob")
    check("a member (bob) can send too", status == 201, f"{status} {body}")

    print("== Group receipts")
    status, body, _ = call(gw, "PUT", "/api/users/me/privacy",
                           {"lastSeen": "EVERYONE", "profilePhoto": "EVERYONE", "about": "EVERYONE",
                            "groupAdd": "EVERYONE", "readReceipts": False}, token=ct)
    check("carol turns read receipts off (does not apply to groups)", status == 200, f"{status} {body}")
    call(gw, "POST", "/api/messages/delivered", {"messageIds": [g1]}, token=bt)
    check("delivered to bob only: still SENT for alice", status_for_sender(at, group, g1) == "SENT")
    call(gw, "POST", "/api/messages/delivered", {"messageIds": [g1]}, token=ct)
    check("delivered to everyone: DELIVERED", status_for_sender(at, group, g1) == "DELIVERED")
    call(gw, "POST", f"/api/conversations/{group}/read", {"upToMessageId": g1}, token=bt)
    check("read by bob only: still DELIVERED", status_for_sender(at, group, g1) == "DELIVERED")
    call(gw, "POST", f"/api/conversations/{group}/read", {"upToMessageId": g1}, token=ct)
    check("read by everyone: READ (read-receipt privacy does not hide it in groups)", status_for_sender(at, group, g1) == "READ")
    status, body, _ = call(gw, "GET", f"/api/messages/{g1}/receipts", token=at)
    check("message info lists both recipients as READ",
          status == 200 and sorted(r["userId"] for r in body["data"]) == sorted(members)
          and all(r["status"] == "READ" and r["readAt"] for r in body["data"]), f"{status} {body}")
    status, body, _ = call(gw, "GET", f"/api/messages/{g1}/receipts", token=bt)
    check("only the sender may see message info (404 for bob)", status == 404, status)

    print("== Admin rules and settings")
    status, body, _ = call(gw, "POST", f"/api/conversations/{group}/members", {"userIds": [alice["id"]]}, token=bt)
    check("a member cannot add people (403 NOT_GROUP_ADMIN)", status == 403 and body["data"]["errorCode"] == "NOT_GROUP_ADMIN", f"{status} {body}")
    status, body, _ = call(gw, "PATCH", f"/api/conversations/{group}/group", {"onlyAdminsCanSend": True}, token=at)
    check("alice allows only admins to send", status == 200 and body["data"]["group"]["onlyAdminsCanSend"] is True, f"{status} {body}")
    status, body, _ = send(bt, group, "can I?")
    check("bob can no longer send (403 ONLY_ADMINS_CAN_SEND)", status == 403 and body["data"]["errorCode"] == "ONLY_ADMINS_CAN_SEND", f"{status} {body}")
    status, body, _ = call(gw, "PATCH", f"/api/conversations/{group}/group", {"onlyAdminsCanSend": False, "name": "Smoke group 2"}, token=at)
    check("settings back, group renamed", status == 200 and body["data"]["group"]["name"] == "Smoke group 2", f"{status} {body}")
    check("rename and settings show up as SYSTEM messages",
          wait_for(lambda: {"NAME_CHANGED", "SETTINGS_CHANGED"} <= set(system_events(bt, group))) is True)

    print("== Removed members (scenario 9)")
    status, body, _ = call(gw, "GET", "/api/messages/sync", token=ct)
    carol_cursor = body["data"]["nextCursor"] if status == 200 else ""
    status, body, _ = call(gw, "DELETE", f"/api/conversations/{group}/members/{carol['id']}", token=at)
    check("alice removes carol", status == 200 and carol["id"] not in [p["id"] for p in body["data"]["participants"]], f"{status} {body}")
    status, body, _ = send(at, group, "Carol must not see this")
    g2 = body["data"]["id"] if status == 201 else None
    status, body, _ = call(gw, "GET", f"/api/conversations/{group}", token=ct)
    check("carol still sees the group, read-only, without the member list",
          status == 200 and body["data"]["membership"]["active"] is False and body["data"]["participants"] == [], f"{status} {body}")
    carol_history = history_ids(ct, group) or []
    check("carol keeps the messages from her membership time", g1 in carol_history, carol_history)
    check("carol does not get the message sent after her removal", g2 is not None and g2 not in carol_history)
    status, body, _ = call(gw, "GET", "/api/messages/sync?after=" + urllib.parse.quote(carol_cursor or ""), token=ct)
    check("...not through sync either", status == 200 and g2 not in [m["id"] for m in body["data"]["items"]], f"{status}")
    status, body, _ = send(ct, group, "still here?")
    check("carol cannot send any more (404)", status == 404, f"{status} {body}")
    check("carol sees the MEMBER_REMOVED system message", wait_for(lambda: "MEMBER_REMOVED" in system_events(ct, group)) is True)

    status, body, _ = call(gw, "POST", f"/api/conversations/{group}/members", {"userIds": [carol["id"]]}, token=at)
    check("alice adds carol again", status == 200 and body["data"]["membership"]["active"] is True, f"{status} {body}")
    status, body, _ = send(at, group, "Welcome back")
    g3 = body["data"]["id"] if status == 201 else None
    carol_history = history_ids(ct, group) or []
    check("carol sees both membership periods but not the gap",
          g1 in carol_history and g3 in carol_history and g2 not in carol_history, carol_history)

    print("== Leaving, automatic admin")
    status, body, _ = call(gw, "PUT", f"/api/conversations/{group}/members/{bob['id']}/role", {"role": "ADMIN"}, token=at)
    check("alice makes bob an admin", status == 200 and bob["id"] in body["data"]["group"]["adminIds"], f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/conversations/{group}/leave", token=at)
    check("alice leaves", status == 200 and body["data"]["membership"]["active"] is False, f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/conversations/{group}/leave", token=bt)
    check("bob (the last admin) leaves", status == 200, f"{status} {body}")
    status, body, _ = call(gw, "GET", f"/api/conversations/{group}", token=ct)
    check("carol, the only one left, became admin automatically",
          status == 200 and body["data"]["membership"]["role"] == "ADMIN", f"{status} {body}")

    print("== Mute, archive, pin")
    status, body, _ = call(gw, "PUT", f"/api/conversations/{private_conv}/mute", {"duration": "ALWAYS"}, token=at)
    check("mute always", status == 200 and (body["data"]["preferences"]["mutedUntil"] or "").startswith("9999"), f"{status} {body}")
    status, body, _ = call(gw, "PUT", f"/api/conversations/{private_conv}/pin", token=at)
    check("pin", status == 200 and body["data"]["preferences"]["pinned"] is True, f"{status} {body}")
    status, body, _ = call(gw, "PUT", f"/api/conversations/{private_conv}/archive", token=at)
    check("archive (also unpins)", status == 200 and body["data"]["preferences"]["archived"] is True
          and body["data"]["preferences"]["pinned"] is False, f"{status} {body}")
    status, body, _ = call(gw, "PUT", f"/api/conversations/{group}/archive", token=at)
    check("a group I left can be archived too", status == 200 and body["data"]["preferences"]["archived"] is True, f"{status} {body}")
    status, body, _ = call(gw, "DELETE", f"/api/conversations/{private_conv}/mute", token=at)
    check("unmute", status == 200 and body["data"]["preferences"]["mutedUntil"] is None, f"{status} {body}")
    status, body, _ = call(gw, "PUT", f"/api/conversations/{private_conv}/mute", {"duration": "ALWAYS"}, token=ct)
    check("someone else's chat cannot be muted (404)", status == 404, status)


def multipart(fields, file_field, filename, content, content_type):
    """Builds a multipart/form-data body (standard library only). Returns (body_bytes, content_type_header)."""
    boundary = "----smoke" + uuid.uuid4().hex
    parts = []
    for name, value in fields.items():
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; filename="{filename}"\r\n'
                  f"Content-Type: {content_type}\r\n\r\n").encode() + content + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def upload(gw, token, purpose, filename, content, content_type, extra=None):
    body, header = multipart({"purpose": purpose, **(extra or {})}, "file", filename, content, content_type)
    request = urllib.request.Request(gw + "/api/media/upload", data=body, method="POST")
    request.add_header("Content-Type", header)
    request.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as error:
        raw = error.read().decode()
        try:
            return error.code, (json.loads(raw) if raw else None)
        except ValueError:
            return error.code, None


def fetch(url):
    """Plain GET of a signed storage link. Returns (status, headers, bytes)."""
    try:
        with urllib.request.urlopen(url, timeout=30) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()


def tiny_png(width=64, height=48):
    """A valid PNG (solid colour) built with zlib: no image library needed."""
    import struct
    import zlib

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    raw = b"".join(b"\x00" + bytes([40, 120, 200]) * width for _ in range(height))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


def run_media_checks(gw, alice, bob, carol, at, bt, ct, conv):
    """Phase 5: upload validation, signed links, attachments in messages, access rules."""
    print("== Media")
    png = tiny_png()
    pdf = b"%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n"

    status, body = upload(gw, at, "IMAGE", "holiday.png", png, "image/png")
    check("alice uploads a PNG (201, thumbnail made)", status == 201 and body["data"]["kind"] == "IMAGE"
          and body["data"]["hasThumbnail"] is True and body["data"]["width"] == 64, f"{status} {body}")
    image = body["data"] if status == 201 else {}

    status, body, _ = call(gw, "GET", f"/api/media/{image.get('id')}", token=at)
    check("the uploader can preview her own upload", status == 200 and body["data"]["url"], f"{status} {body}")
    if status == 200:
        s, headers, data = fetch(body["data"]["url"])
        check("the signed link serves exactly the uploaded bytes with the right type", s == 200 and data == png
              and headers.get("Content-Type", "").startswith("image/png"), f"{s} {headers.get('Content-Type')}")
        s, headers, data = fetch(body["data"]["thumbnailUrl"])
        check("the thumbnail link serves a JPEG", s == 200 and data[:3] == b"\xff\xd8\xff", f"{s} {data[:4]}")
    status, body, _ = call(gw, "GET", f"/api/media/{image.get('id')}", token=bt)
    check("someone else cannot open an unattached upload (404)", status == 404, status)

    status, body = upload(gw, at, "IMAGE", "photo.png", b"MZ\x90\x00" + b"\x00" * 200, "image/png")
    check("an .exe renamed .png is refused (415)", status == 415, f"{status} {body}")
    status, body = upload(gw, at, "IMAGE", "doc.png", pdf, "image/png")
    check("a PDF uploaded as an image is refused (415)", status == 415, f"{status} {body}")
    status, body = upload(gw, at, "DOCUMENT", "page.txt", b"<html><script>alert(1)</script></html>", "text/plain")
    check("HTML disguised as text is refused", status == 415, f"{status} {body}")
    status, body = upload(gw, at, "IMAGE", "big.png", png + b"\x00" * (10 * 1024 * 1024 + 10), "image/png")
    check("an image over 10 MB is refused (413)", status == 413, f"{status} {str(body)[:80]}")
    status, body = upload(gw, at, "IMAGE", "empty.png", b"", "image/png")
    check("an empty file is refused (400)", status == 400, f"{status} {body}")

    status, body = upload(gw, at, "DOCUMENT", "report.pdf", pdf, "application/pdf")
    check("alice uploads a PDF", status == 201 and body["data"]["kind"] == "DOCUMENT" and body["data"]["hasThumbnail"] is False, f"{status} {body}")
    document = body["data"] if status == 201 else {}

    key = uuid.uuid4().hex
    msg = {"conversationId": conv, "type": "IMAGE", "content": "Look at this", "attachmentId": image.get("id"), "idempotencyKey": key}
    status, body, _ = call(gw, "POST", "/api/messages", msg, token=at)
    check("alice sends the image (201, attachment metadata in the message)", status == 201
          and body["data"]["attachment"]["fileName"] == "holiday.png" and body["data"]["content"] == "Look at this", f"{status} {body}")
    image_message = body["data"]["id"] if status == 201 else None
    status, body, _ = call(gw, "POST", "/api/messages", msg, token=at)
    check("retrying the same send returns the same message (200)", status == 200 and body["data"]["id"] == image_message, f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/messages", {**msg, "idempotencyKey": uuid.uuid4().hex}, token=at)
    check("a file cannot be used by a second message (409)", status == 409 and body["data"]["errorCode"] == "ATTACHMENT_ALREADY_USED", f"{status} {body}")
    status, body, _ = call(gw, "DELETE", f"/api/media/{image.get('id')}", token=at)
    check("a file that is in use cannot be deleted (409)", status == 409, f"{status} {body}")

    status, body, _ = call(gw, "POST", "/api/messages", {"conversationId": conv, "type": "DOCUMENT", "attachmentId": document.get("id"),
                                                         "idempotencyKey": uuid.uuid4().hex}, token=at)
    check("alice sends the PDF without a caption", status == 201 and body["data"]["content"] == "", f"{status} {body}")
    document_message = body["data"]["id"] if status == 201 else None

    status, body, _ = call(gw, "POST", "/api/messages", {"conversationId": conv, "type": "IMAGE", "attachmentId": document.get("id"),
                                                         "idempotencyKey": uuid.uuid4().hex}, token=at)
    check("a used PDF cannot become an image message (409)", status in (400, 409), f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/messages", {"conversationId": conv, "type": "IMAGE", "attachmentId": str(uuid.uuid4()),
                                                         "idempotencyKey": uuid.uuid4().hex}, token=at)
    check("an unknown attachment is 404", status == 404 and body["data"]["errorCode"] == "ATTACHMENT_NOT_FOUND", f"{status} {body}")
    status, body = upload(gw, at, "IMAGE", "other.png", png, "image/png")
    alices_second = body["data"]["id"] if status == 201 else None
    status, body, _ = call(gw, "POST", "/api/messages", {"conversationId": conv, "type": "IMAGE", "attachmentId": alices_second,
                                                         "idempotencyKey": uuid.uuid4().hex}, token=bt)
    check("bob cannot send alice's upload (404)", status == 404, f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/messages", {"conversationId": conv, "type": "VIDEO", "attachmentId": alices_second,
                                                         "idempotencyKey": uuid.uuid4().hex}, token=at)
    check("the message type must match the file (400)", status == 400 and body["data"]["errorCode"] == "ATTACHMENT_TYPE_MISMATCH", f"{status} {body}")

    status, body, _ = call(gw, "GET", f"/api/conversations/{conv}/messages?limit=5", token=bt)
    attachments = [m["attachment"]["fileName"] for m in body["data"]["items"] if m.get("attachment")] if status == 200 else []
    check("bob's history shows both attachments", sorted(attachments) == ["holiday.png", "report.pdf"], f"{status} {attachments}")

    status, body, _ = call(gw, "GET", f"/api/messages/{image_message}/attachment/url", token=bt)
    check("bob gets a signed link for the image", status == 200 and body["data"]["url"] and body["data"]["expiresAt"], f"{status} {body}")
    if status == 200:
        s, headers, data = fetch(body["data"]["url"])
        check("bob downloads exactly alice's image", s == 200 and data == png, f"{s}")
    status, body, _ = call(gw, "GET", f"/api/messages/{image_message}/attachment/url?variant=thumbnail", token=bt)
    if status == 200:
        s, headers, data = fetch(body["data"]["url"])
        check("bob gets the thumbnail too", s == 200 and data[:3] == b"\xff\xd8\xff", f"{s}")
    status, body, _ = call(gw, "GET", f"/api/messages/{document_message}/attachment/url", token=bt)
    if status == 200:
        s, headers, data = fetch(body["data"]["url"])
        disposition = headers.get("Content-Disposition") or ""
        check("a document is served as a download (Content-Disposition: attachment) with its name",
              s == 200 and data == pdf and "attachment" in disposition and "report.pdf" in disposition, f"{s} {disposition}")
    status, body, _ = call(gw, "GET", f"/api/messages/{image_message}/attachment/url", token=ct)
    check("a stranger gets no link (404)", status == 404, f"{status} {body}")
    status, body, _ = call(gw, "GET", f"/api/messages/{image_message}/attachment/url?variant=nope", token=bt)
    check("an unknown variant is 400", status == 400, f"{status} {body}")


def run_presence_checks(gw, alice, bob, carol, at, bt, ct):
    """Phase 6: heartbeats, several devices, expiry is covered by the unit tests (60 s), privacy rules."""
    print("== Presence")
    everyone = {"lastSeen": "EVERYONE", "profilePhoto": "EVERYONE", "about": "EVERYONE", "groupAdd": "EVERYONE", "readReceipts": True}
    call(gw, "PUT", "/api/users/me/privacy", everyone, token=at)
    call(gw, "PUT", "/api/users/me/privacy", everyone, token=bt)

    def see(viewer_token, target):
        s, b, _ = call(gw, "GET", f"/api/presence/{target['id']}", token=viewer_token)
        return b["data"] if s == 200 else {"status": s}

    p = see(bt, alice)
    check("a user who never sent a heartbeat is offline, with no last seen", p.get("visible") is True and p.get("online") is False
          and p.get("lastSeenAt") is None, p)
    status, body, _ = call(gw, "POST", "/api/presence/heartbeat", token=at)
    check("alice sends a heartbeat", status == 200, f"{status} {body}")
    p = see(bt, alice)
    check("bob sees alice online", p.get("online") is True and p.get("lastSeenAt") is not None, p)

    # a second device of alice (another login session)
    status, body, _ = call(gw, "POST", "/api/auth/login", {"identifier": alice["email"], "password": "Passw0rd123", "deviceName": "Smoke phone"})
    at2 = body["data"]["accessToken"] if status == 200 else None
    check("alice logs in on a second device", at2 is not None, f"{status} {body}")
    call(gw, "POST", "/api/presence/heartbeat", token=at2)
    call(gw, "POST", "/api/presence/offline", token=at)
    check("one device goes offline: alice is still online (the other device is alive)", see(bt, alice).get("online") is True)
    call(gw, "POST", "/api/presence/offline", token=at2)
    p = see(bt, alice)
    check("all devices offline: alice is offline and has a last seen time", p.get("online") is False and p.get("lastSeenAt") is not None, p)

    call(gw, "POST", "/api/presence/heartbeat", token=at)
    status, body, _ = call(gw, "PUT", "/api/users/me/privacy", {**everyone, "lastSeen": "NOBODY"}, token=at)
    p = see(bt, alice)
    check("alice hides her status (NOBODY): bob gets nothing", p.get("visible") is False and p.get("online") is None
          and p.get("lastSeenAt") is None, p)
    p = see(at, alice)
    check("alice still sees herself", p.get("visible") is True and p.get("online") is True, p)
    call(gw, "POST", "/api/presence/heartbeat", token=bt)
    p = see(at, bob)
    check("reciprocity: someone who hides their own status cannot see others'", p.get("visible") is False, p)

    call(gw, "PUT", "/api/users/me/privacy", {**everyone, "lastSeen": "CONTACTS"}, token=at)
    p = see(bt, alice)
    check("CONTACTS: bob is not alice's contact, so he sees nothing", p.get("visible") is False, p)
    status, body, _ = call(gw, "POST", "/api/contacts", {"contactUserId": bob["id"]}, token=at)
    contact_id = body["data"]["id"] if status == 201 else None
    p = see(bt, alice)
    check("CONTACTS: after alice adds bob as a contact, bob sees her online", p.get("visible") is True and p.get("online") is True, p)
    if contact_id:
        call(gw, "DELETE", f"/api/contacts/{contact_id}", token=at)
    call(gw, "PUT", "/api/users/me/privacy", everyone, token=at)

    call(gw, "POST", f"/api/users/{bob['id']}/block", token=at)
    p = see(bt, alice)
    check("a blocked user sees nothing of the blocker", p.get("visible") is False, p)
    call(gw, "DELETE", f"/api/users/{bob['id']}/block", token=at)

    status, body, _ = call(gw, "GET", f"/api/presence/{alice['id']}")
    check("presence needs a token (401)", status == 401, status)
    status, body, _ = call(gw, "GET", "/api/presence/not-a-uuid", token=bt)
    check("a bad user id is 400", status == 400, status)
    call(gw, "POST", "/api/presence/offline", token=at)
    call(gw, "POST", "/api/presence/offline", token=bt)


def run_notification_checks(gw, alice, bob, carol, at, bt, ct, conv):
    """Phase 6: notifications are made from Kafka events (so we poll), only for offline unmuted users who allow them."""
    print("== Notifications")
    marker = "marker-" + uuid.uuid4().hex[:10]

    def send(text):
        s, b, _ = call(gw, "POST", "/api/messages", {"conversationId": conv, "type": "TEXT", "content": text,
                                                     "idempotencyKey": uuid.uuid4().hex}, token=at)
        return b["data"]["id"] if s == 201 else None

    def notifications(token, size=20):
        s, b, _ = call(gw, "GET", f"/api/notifications?size={size}", token=token)
        return b["data"] if s == 200 else {"items": [], "status": s}

    def chat_notification(token, read=None):
        items = [n for n in notifications(token)["items"] if n["type"] == "NEW_MESSAGES" and n["conversationId"] == conv]
        if read is not None:
            items = [n for n in items if n["read"] is read]
        return items[0] if items else None

    def unread_count(token):
        s, b, _ = call(gw, "GET", "/api/notifications/unread-count", token=token)
        return b["data"]["unreadCount"] if s == 200 else None

    call(gw, "POST", "/api/presence/offline", token=bt)         # bob has no live device
    # Bob was offline during the whole test, so earlier sections already created (aggregated) notifications.
    # Start from a clean slate: everything read.
    status, body, _ = call(gw, "PUT", "/api/notifications/read-all", token=bt)
    check("bob's notifications from the earlier sections exist and can be marked read", status == 200 and body["data"]["updated"] >= 1, f"{status} {body}")

    status, body, _ = call(gw, "GET", "/api/notifications/preferences", token=bt)
    check("preferences default to everything on", status == 200 and all(body["data"][k] is True for k in
          ("pushEnabled", "messagesEnabled", "groupsEnabled", "callsEnabled")), f"{status} {body}")

    send(f"first {marker}")
    send(f"second {marker}")
    note = wait_for(lambda: (chat_notification(bt, read=False) or {}).get("count") == 2 and chat_notification(bt, read=False), timeout=25)
    check("offline bob gets ONE aggregated notification for the chat (count 2)", bool(note), notifications(bt))
    if note:
        check("the notification never contains the message text", marker not in json.dumps(note), note)
        check("its title names the sender (not empty) and the body counts messages",
              bool(note["title"]) and "2 new messages" in note["body"], note)
    check("bob's unread count includes it", (unread_count(bt) or 0) >= 1, unread_count(bt))

    status, body, _ = call(gw, "GET", "/api/notifications?size=1", token=bt)
    check("the list is paged (size 1 -> hasMore or exactly one item)", status == 200 and len(body["data"]["items"]) == 1, f"{status} {body}")

    if note:
        status, body, _ = call(gw, "PUT", f"/api/notifications/{note['id']}/read", token=at)
        check("alice cannot read bob's notification (404)", status == 404, f"{status} {body}")
        before = unread_count(bt)
        status, body, _ = call(gw, "PUT", f"/api/notifications/{note['id']}/read", token=bt)
        check("bob marks it read", status == 200 and body["data"]["read"] is True, f"{status} {body}")
        check("the unread count went down", unread_count(bt) == before - 1, f"{before} -> {unread_count(bt)}")

    # an online recipient needs no notification
    call(gw, "POST", "/api/presence/heartbeat", token=bt)
    send("bob is online " + marker)
    time.sleep(4)
    check("an ONLINE recipient gets no new notification", chat_notification(bt, read=False) is None, chat_notification(bt, read=False))
    call(gw, "POST", "/api/presence/offline", token=bt)

    # a muted chat gives none
    status, body, _ = call(gw, "PUT", f"/api/conversations/{conv}/mute", {"duration": "ALWAYS"}, token=bt)
    check("bob mutes the chat", status == 200, f"{status} {body}")
    send("bob muted this " + marker)
    time.sleep(4)
    check("a MUTED chat gives no notification", chat_notification(bt, read=False) is None, chat_notification(bt, read=False))
    call(gw, "DELETE", f"/api/conversations/{conv}/mute", token=bt)

    # preferences are respected
    prefs = {"pushEnabled": True, "messagesEnabled": False, "groupsEnabled": True, "callsEnabled": True}
    status, body, _ = call(gw, "PUT", "/api/notifications/preferences", prefs, token=bt)
    check("bob turns message notifications off", status == 200 and body["data"]["messagesEnabled"] is False, f"{status} {body}")
    send("prefs off " + marker)
    time.sleep(4)
    check("with messagesEnabled off there is no notification", chat_notification(bt, read=False) is None, chat_notification(bt, read=False))
    call(gw, "PUT", "/api/notifications/preferences", {**prefs, "messagesEnabled": True}, token=bt)

    # reading the chat clears its notification
    last = send("read me " + marker)
    note = wait_for(lambda: chat_notification(bt, read=False), timeout=25)
    check("a new message creates a fresh notification after the old one was read", bool(note), notifications(bt))
    status, body, _ = call(gw, "POST", f"/api/conversations/{conv}/read", {"upToMessageId": last}, token=bt)
    check("bob reads the chat", status == 200, f"{status} {body}")
    cleared = wait_for(lambda: chat_notification(bt, read=False) is None, timeout=25)
    check("reading the chat marks its notification read", bool(cleared), chat_notification(bt, read=False))

    # group notifications (also for online users)
    group_name = "Notified group " + uuid.uuid4().hex[:6]
    status, body, _ = call(gw, "POST", "/api/conversations", {"type": "GROUP", "name": group_name, "memberIds": [bob["id"]]}, token=at)
    check("alice creates a group with bob", status == 201, f"{status} {body}")
    added = wait_for(lambda: next((n for n in notifications(bt)["items"] if n["type"] == "GROUP_ADDED" and n["title"] == group_name), None), timeout=25)
    check("bob is told he was added to the group", bool(added) and "added you" in added["body"], notifications(bt))
    call(gw, "PUT", "/api/notifications/preferences", {**prefs, "messagesEnabled": True, "groupsEnabled": False}, token=bt)
    second_name = "Silent group " + uuid.uuid4().hex[:6]
    call(gw, "POST", "/api/conversations", {"type": "GROUP", "name": second_name, "memberIds": [bob["id"]]}, token=at)
    time.sleep(4)
    check("with groupsEnabled off there is no group notification",
          not any(n["title"] == second_name for n in notifications(bt)["items"]), notifications(bt))
    call(gw, "PUT", "/api/notifications/preferences", {**prefs, "messagesEnabled": True, "groupsEnabled": True}, token=bt)

    status, body, _ = call(gw, "PUT", "/api/notifications/read-all", token=bt)
    check("mark all as read", status == 200 and unread_count(bt) == 0, f"{status} {body}")

    token = "smoke-device-token-" + uuid.uuid4().hex
    status, body, _ = call(gw, "POST", "/api/notifications/device-tokens", {"token": token, "platform": "WEB"}, token=bt)
    check("bob registers a device token", status == 200, f"{status} {body}")
    status, body, _ = call(gw, "DELETE", "/api/notifications/device-tokens?token=" + urllib.parse.quote(token), token=bt)
    check("and removes it", status == 200, f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/notifications/device-tokens", {"token": "short", "platform": "WEB"}, token=bt)
    check("a too short token is 400", status == 400, f"{status} {body}")
    status, body, _ = call(gw, "GET", "/api/notifications")
    check("notifications need a token (401)", status == 401, status)


def run_call_checks(gw, alice, bob, carol, at, bt, ct, password):
    """Phase 7: call state, busy, devices, signaling rules, time-outs, history and missed-call notifications.
    (The audio/video itself is browser to browser; the Playwright calls spec checks that part.)"""
    print("== Calls")

    def start(token, callee, kind="VIDEO"):
        return call(gw, "POST", "/api/calls", {"calleeId": callee, "type": kind}, token=token)

    def missed_calls(token):
        s, b, _ = call(gw, "GET", "/api/notifications?size=50", token=token)
        return [n for n in (b["data"]["items"] if s == 200 else []) if n["type"] == "MISSED_CALL"]

    status, body, _ = call(gw, "POST", "/api/auth/login", {"identifier": bob["email"], "password": password, "deviceName": "Bob phone"})
    bt2 = body["data"]["accessToken"] if status == 200 else None
    check("bob logs in on a second device", bt2 is not None, status)

    status, body, _ = start(at, alice["id"])
    check("calling yourself is 400", status == 400, f"{status} {body}")
    status, body, _ = start(at, str(uuid.uuid4()))
    check("calling an unknown user is 404", status == 404, f"{status} {body}")
    call(gw, "POST", f"/api/users/{alice['id']}/block", token=ct)
    status, body, _ = start(at, carol["id"])
    check("calling someone who blocked you is 403 CALL_NOT_ALLOWED", status == 403 and body["data"]["errorCode"] == "CALL_NOT_ALLOWED", f"{status} {body}")
    call(gw, "DELETE", f"/api/users/{alice['id']}/block", token=ct)

    status, body, _ = start(at, bob["id"], "VIDEO")
    session = body["data"] if status == 201 else None
    check("alice video-calls bob: 201 RINGING", session is not None and session["call"]["status"] == "RINGING"
          and session["call"]["direction"] == "OUTGOING" and session["call"]["otherUser"]["id"] == bob["id"], f"{status} {body}")
    if not session:
        return
    call_id = session["call"]["id"]
    urls = [u for server in session["iceServers"] for u in server["urls"]]
    turn = next((s for s in session["iceServers"] if any(u.startswith("turn:") for u in s["urls"])), None)
    check("ICE servers: STUN plus TURN (udp and tcp) with short-lived credentials",
          any(u.startswith("stun:") for u in urls) and turn is not None
          and any("transport=udp" in u for u in turn["urls"]) and any("transport=tcp" in u for u in turn["urls"])
          and turn["username"].endswith(":" + alice["id"]) and bool(turn["credential"]), session["iceServers"])
    if turn:
        expiry = int(turn["username"].split(":")[0])
        check("the TURN credential expires in about 8 hours", 7 * 3600 < expiry - time.time() < 9 * 3600, expiry - time.time())

    status, body, _ = call(gw, "GET", "/api/calls/active", token=bt)
    check("bob's active call is the incoming one", status == 200 and body["data"] and body["data"]["id"] == call_id
          and body["data"]["direction"] == "INCOMING", f"{status} {body}")
    status, body, _ = call(gw, "GET", f"/api/calls/{call_id}", token=ct)
    check("a stranger cannot see the call (404)", status == 404, status)
    status, body, _ = start(at, carol["id"])
    check("alice cannot start a second call (409 ALREADY_IN_CALL)", status == 409 and body["data"]["errorCode"] == "ALREADY_IN_CALL", f"{status} {body}")
    status, body, _ = start(ct, bob["id"], "AUDIO")
    check("carol calling busy bob gets BUSY at once (no ICE servers)", status == 201 and body["data"]["call"]["status"] == "BUSY"
          and body["data"]["iceServers"] == [], f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/accept", token=at)
    check("the caller cannot accept (403 NOT_CALLEE)", status == 403 and body["data"]["errorCode"] == "NOT_CALLEE", f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/signal", {"type": "OFFER", "sdp": "v=0"}, token=at)
    check("no signaling before the call is accepted (409 CALL_NOT_ACTIVE)", status == 409 and body["data"]["errorCode"] == "CALL_NOT_ACTIVE", f"{status} {body}")

    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/accept", token=bt2)
    check("bob accepts on his second device: ACCEPTED, with ICE servers", status == 200 and body["data"]["call"]["status"] == "ACCEPTED"
          and len(body["data"]["iceServers"]) >= 2, f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/accept", token=bt)
    check("his first device can no longer accept (409 CALL_NOT_RINGING)", status == 409 and body["data"]["errorCode"] == "CALL_NOT_RINGING", f"{status} {body}")

    sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/signal", {"type": "OFFER", "sdp": sdp}, token=at)
    check("alice (caller device) sends the OFFER", status == 200, f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/signal", {"type": "ANSWER", "sdp": sdp}, token=bt)
    check("bob's other device may not signal (403 NOT_CALL_DEVICE)", status == 403 and body["data"]["errorCode"] == "NOT_CALL_DEVICE", f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/signal", {"type": "ANSWER", "sdp": sdp}, token=bt2)
    check("bob's call device sends the ANSWER", status == 200, f"{status} {body}")
    candidate = {"candidate": "candidate:1 1 udp 2122260223 192.168.1.2 50000 typ host", "sdpMid": "0", "sdpMLineIndex": 0}
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/signal", {"type": "ICE", "candidates": [candidate] * 3}, token=bt2)
    check("a batch of ICE candidates is relayed", status == 200, f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/signal", {"type": "ICE", "candidates": [candidate] * 51}, token=bt2)
    check("more than 50 candidates in one batch is 400", status == 400, f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/signal", {"type": "OFFER"}, token=at)
    check("an OFFER without sdp is 400", status == 400, f"{status} {body}")
    check("both call devices can ping", call(gw, "POST", f"/api/calls/{call_id}/ping", token=at)[0] == 200
          and call(gw, "POST", f"/api/calls/{call_id}/ping", token=bt2)[0] == 200)

    time.sleep(1.2)
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/end", token=at)
    check("alice hangs up: ENDED / HANGUP with a duration", status == 200 and body["data"]["status"] == "ENDED"
          and body["data"]["endReason"] == "HANGUP" and body["data"]["durationSeconds"] >= 1, f"{status} {body}")
    status, body, _ = call(gw, "POST", f"/api/calls/{call_id}/end", token=bt2)
    check("ending again is harmless (200, unchanged)", status == 200 and body["data"]["status"] == "ENDED", f"{status} {body}")
    status, body, _ = call(gw, "GET", "/api/calls/active", token=bt)
    check("nobody is in a call any more", status == 200 and body["data"] is None, f"{status} {body}")

    status, body, _ = call(gw, "GET", "/api/calls/history?size=5", token=bt)
    items = body["data"]["items"] if status == 200 else []
    check("bob's history: the answered call (incoming) and carol's busy call",
          any(c["id"] == call_id and c["direction"] == "INCOMING" and c["status"] == "ENDED" for c in items)
          and any(c["status"] == "BUSY" and c["otherUser"]["id"] == carol["id"] for c in items), items)

    print("== Missed calls")
    status, body, _ = start(at, bob["id"], "AUDIO")
    cancelled = body["data"]["call"]["id"] if status == 201 else None
    status, body, _ = call(gw, "POST", f"/api/calls/{cancelled}/end", token=at)
    check("alice cancels while it rings: MISSED / CANCELLED", status == 200 and body["data"]["status"] == "MISSED"
          and body["data"]["endReason"] == "CANCELLED", f"{status} {body}")
    note = wait_for(lambda: next((n for n in missed_calls(bt) if n["body"] == "Missed voice call" and n["title"] != carol["displayName"]), None), timeout=25)
    check("bob gets a MISSED_CALL notification 'Missed voice call' with alice's name", bool(note) and bool(note["title"])
          and note["conversationId"] is None, missed_calls(bt))
    check("the busy call also became a missed-call notification for bob",
          wait_for(lambda: any(n["body"] == "Missed voice call" and n["title"] == carol["displayName"] for n in missed_calls(bt)), timeout=25) is True,
          missed_calls(bt))

    status, body, _ = start(at, bob["id"], "VIDEO")
    declined = body["data"]["call"]["id"] if status == 201 else None
    status, body, _ = call(gw, "POST", f"/api/calls/{declined}/reject", token=bt)
    check("bob declines: REJECTED / DECLINED", status == 200 and body["data"]["status"] == "REJECTED"
          and body["data"]["endReason"] == "DECLINED", f"{status} {body}")

    prefs = {"pushEnabled": True, "messagesEnabled": True, "groupsEnabled": True, "callsEnabled": False}
    call(gw, "PUT", "/api/notifications/preferences", prefs, token=bt)
    before = len(missed_calls(bt))
    status, body, _ = start(at, bob["id"], "VIDEO")
    quiet = body["data"]["call"]["id"] if status == 201 else None
    call(gw, "POST", f"/api/calls/{quiet}/end", token=at)
    time.sleep(4)
    check("with callsEnabled off a missed call gives no notification", len(missed_calls(bt)) == before, missed_calls(bt))
    call(gw, "PUT", "/api/notifications/preferences", {**prefs, "callsEnabled": True}, token=bt)

    status, body, _ = start(at, bob["id"], "VIDEO")
    unanswered = body["data"]["call"]["id"] if status == 201 else None
    print("  ...  waiting for the 30 s ring timeout")
    ended = wait_for(lambda: (call(gw, "GET", f"/api/calls/{unanswered}", token=at)[1] or {}).get("data", {}).get("status") == "MISSED", timeout=45)
    status, body, _ = call(gw, "GET", f"/api/calls/{unanswered}", token=at)
    check("an unanswered call becomes MISSED / NO_ANSWER after 30 s", bool(ended) and body["data"]["endReason"] == "NO_ANSWER", f"{status} {body}")
    check("after the time-out alice can call again (busy slots were released)",
          start(at, bob["id"], "AUDIO")[0] == 201)
    status, body, _ = call(gw, "GET", "/api/calls/active", token=at)
    if status == 200 and body["data"]:
        call(gw, "POST", f"/api/calls/{body['data']['id']}/end", token=at)
    status, body, _ = call(gw, "GET", "/api/calls/history")
    check("calls need a token (401)", status == 401, status)


def run_status_checks(gw, alice, bob, carol, at, bt, ct):
    """Phase 8: statuses (text / photo), who may see them, views and viewers, privacy modes, delete, the 30 limit.
    (The 24 h expiry is covered by status-service's tests with a short lifetime.)"""
    print("== Status")

    def post_status(token, body):
        return call(gw, "POST", "/api/statuses", {"idempotencyKey": uuid.uuid4().hex, **body}, token=token)

    def alice_group(token):
        s, b, _ = call(gw, "GET", "/api/statuses/feed", token=token)
        return next((g for g in (b["data"] if s == 200 else []) if g["owner"]["id"] == alice["id"]), None)

    def set_privacy(mode, user_ids):
        return call(gw, "PUT", "/api/statuses/privacy", {"mode": mode, "userIds": user_ids}, token=at)

    def privacy(token, read_receipts):
        return call(gw, "PUT", "/api/users/me/privacy", {"lastSeen": "EVERYONE", "profilePhoto": "EVERYONE", "about": "EVERYONE",
                                                          "groupAdd": "EVERYONE", "readReceipts": read_receipts}, token=token)

    # audience = alice's contact list: bob in it, carol not
    call(gw, "POST", "/api/contacts", {"contactUserId": bob["id"]}, token=at)
    status, body, _ = call(gw, "GET", "/api/contacts", token=at)
    for contact in (body["data"] if status == 200 else []):
        if contact["contactUserId"] == carol["id"]:
            call(gw, "DELETE", f"/api/contacts/{contact['id']}", token=at)
    privacy(bt, True)
    privacy(at, True)

    key = uuid.uuid4().hex
    text_body = {"type": "TEXT", "text": "Hello from the smoke test", "backgroundColor": "#0F766E", "idempotencyKey": key}
    status, body, _ = call(gw, "POST", "/api/statuses", text_body, token=at)
    text_status = body["data"] if status == 201 else None
    check("alice posts a text status (201, expires in 24 h)", text_status is not None and text_status["type"] == "TEXT"
          and text_status["viewedByMe"] is True, f"{status} {body}")
    if not text_status:
        return
    status, body, _ = call(gw, "POST", "/api/statuses", text_body, token=at)
    check("posting again with the same key returns the same status (200)", status == 200 and body["data"]["id"] == text_status["id"], f"{status} {body}")
    status, body, _ = post_status(at, {"type": "TEXT", "text": "x", "backgroundColor": "#123456"})
    check("a colour outside the list is 400", status == 400, f"{status} {body}")
    status, body, _ = post_status(at, {"type": "TEXT", "text": "x" * 701, "backgroundColor": "#0F766E"})
    check("text over 700 characters is 400", status == 400, f"{status} {body}")

    status, body = upload(gw, at, "IMAGE", "status.png", tiny_png(), "image/png")
    media_id = body["data"]["id"] if status in (200, 201) and body and body.get("data") else None
    check("alice uploads a photo for her status", media_id is not None, f"{status} {body}")
    status, body, _ = post_status(at, {"type": "VIDEO", "mediaId": media_id})
    check("a photo posted as VIDEO is 400 ATTACHMENT_TYPE_MISMATCH", status == 400 and body["data"]["errorCode"] == "ATTACHMENT_TYPE_MISMATCH", f"{status} {body}")
    status, body, _ = post_status(at, {"type": "IMAGE", "mediaId": media_id, "caption": "Sunset"})
    photo_status = body["data"] if status == 201 else None
    check("alice posts a photo status with a caption", photo_status is not None and photo_status["media"]["kind"] == "IMAGE"
          and photo_status["caption"] == "Sunset", f"{status} {body}")
    status, body = upload(gw, bt, "IMAGE", "bobs.png", tiny_png(), "image/png")
    bobs_media = body["data"]["id"] if status in (200, 201) and body and body.get("data") else str(uuid.uuid4())
    status, body, _ = post_status(at, {"type": "IMAGE", "mediaId": bobs_media})
    check("someone else's upload cannot be used (404 ATTACHMENT_NOT_FOUND)", status == 404 and body["data"]["errorCode"] == "ATTACHMENT_NOT_FOUND", f"{status} {body}")
    if not photo_status:
        return

    group = alice_group(bt)
    check("bob (in alice's contacts) sees her 2 statuses, not yet viewed",
          group is not None and [s["id"] for s in group["statuses"]] == [text_status["id"], photo_status["id"]]
          and group["allViewed"] is False, group)
    check("carol (not a contact) does not see them", alice_group(ct) is None)
    status, body, _ = call(gw, "POST", f"/api/statuses/{text_status['id']}/view", token=ct)
    check("carol cannot view one (404)", status == 404, status)
    status, body, _ = call(gw, "GET", f"/api/statuses/{photo_status['id']}/media-url?variant=original", token=ct)
    check("carol cannot get its photo (404)", status == 404, status)

    status, body, _ = call(gw, "GET", f"/api/statuses/{photo_status['id']}/media-url?variant=thumbnail", token=bt)
    thumb_url = body["data"]["url"] if status == 200 else None
    check("bob gets a signed thumbnail link that works", thumb_url is not None and fetch(thumb_url)[0] == 200, f"{status} {body}")
    status, body, _ = call(gw, "GET", f"/api/statuses/{photo_status['id']}/media-url?variant=original", token=bt)
    photo_url = body["data"]["url"] if status == 200 else None
    check("bob gets a signed link to the photo that works", photo_url is not None and fetch(photo_url)[0] == 200, f"{status} {body}")

    for status_id in (text_status["id"], photo_status["id"], text_status["id"]):
        last = call(gw, "POST", f"/api/statuses/{status_id}/view", token=bt)[0]
    check("bob views both (viewing again is fine)", last == 200, last)
    group = alice_group(bt)
    check("for bob the group is now fully viewed", group is not None and group["allViewed"] is True
          and all(s["viewedByMe"] for s in group["statuses"]), group)

    status, body, _ = call(gw, "GET", "/api/statuses/mine", token=at)
    mine = {s["id"]: s for s in (body["data"] if status == 200 else [])}
    check("alice's own list counts 1 view on each", mine.get(text_status["id"], {}).get("viewCount") == 1
          and mine.get(photo_status["id"], {}).get("viewCount") == 1, f"{status} {body}")
    status, body, _ = call(gw, "GET", f"/api/statuses/{text_status['id']}/viewers", token=at)
    check("alice sees bob in the viewers list", status == 200 and body["data"]["viewersHidden"] is False
          and [v["user"]["id"] for v in body["data"]["viewers"]] == [bob["id"]], f"{status} {body}")
    status, body, _ = call(gw, "GET", f"/api/statuses/{text_status['id']}/viewers", token=bt)
    check("only the owner sees the viewers (404 for bob)", status == 404, status)

    privacy(bt, False)
    status, body, _ = call(gw, "GET", f"/api/statuses/{text_status['id']}/viewers", token=at)
    check("bob turns read receipts off: he is no longer listed or counted", status == 200
          and body["data"]["viewCount"] == 0 and body["data"]["viewers"] == [], f"{status} {body}")
    privacy(bt, True)
    privacy(at, False)
    status, body, _ = call(gw, "GET", f"/api/statuses/{text_status['id']}/viewers", token=at)
    check("alice turns read receipts off: her viewers are hidden", status == 200 and body["data"]["viewersHidden"] is True
          and body["data"]["viewers"] == [], f"{status} {body}")
    privacy(at, True)

    print("== Status privacy")
    status, body, _ = call(gw, "GET", "/api/statuses/privacy", token=at)
    check("default status privacy is CONTACTS", status == 200 and body["data"]["mode"] == "CONTACTS" and body["data"]["userIds"] == [], f"{status} {body}")
    status, body, _ = set_privacy("CONTACTS_EXCEPT", [bob["id"]])
    check("alice hides her status from bob (contacts except)", status == 200 and body["data"]["userIds"] == [bob["id"]], f"{status} {body}")
    check("bob no longer sees it", alice_group(bt) is None)
    check("...and cannot open one (404)", call(gw, "POST", f"/api/statuses/{text_status['id']}/view", token=bt)[0] == 404)
    set_privacy("ONLY_SHARE_WITH", [carol["id"]])
    check("'only share with' a non-contact shows nothing to carol", alice_group(ct) is None)
    set_privacy("ONLY_SHARE_WITH", [bob["id"]])
    check("'only share with' bob shows it to bob again", alice_group(bt) is not None)
    status, body, _ = set_privacy("CONTACTS", [bob["id"]])
    check("CONTACTS with a list is 400", status == 400, f"{status} {body}")
    set_privacy("CONTACTS", [])
    call(gw, "POST", f"/api/users/{alice['id']}/block", token=bt)
    check("when bob blocks alice he does not see her statuses", alice_group(bt) is None)
    call(gw, "DELETE", f"/api/users/{alice['id']}/block", token=bt)
    check("after unblocking he sees them again", alice_group(bt) is not None)

    print("== Status delete and limit")
    status, body, _ = call(gw, "DELETE", f"/api/statuses/{photo_status['id']}", token=bt)
    check("bob cannot delete alice's status (404)", status == 404, status)
    status, body, _ = call(gw, "DELETE", f"/api/statuses/{photo_status['id']}", token=at)
    check("alice deletes her photo status", status == 200, f"{status} {body}")
    group = alice_group(bt)
    check("bob now sees only the text status", group is not None and [s["id"] for s in group["statuses"]] == [text_status["id"]], group)
    gone = wait_for(lambda: fetch(photo_url)[0] != 200, timeout=10) if photo_url else False
    check("the deleted status's photo file is gone from the storage", bool(gone))

    codes = [post_status(ct, {"type": "TEXT", "text": f"carol {n}", "backgroundColor": "#334155"})[0] for n in range(30)]
    status, body, _ = post_status(ct, {"type": "TEXT", "text": "one too many", "backgroundColor": "#334155"})
    check("30 active statuses are allowed, the 31st is 409 STATUS_LIMIT_REACHED",
          codes.count(201) == 30 and status == 409 and body["data"]["errorCode"] == "STATUS_LIMIT_REACHED", f"{codes.count(201)} {status} {body}")
    status, body, _ = call(gw, "GET", "/api/statuses/feed")
    check("statuses need a token (401)", status == 401, status)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8200")
    parser.add_argument("--mailhog", default="http://localhost:8025")
    args = parser.parse_args()
    gw = args.gateway

    suffix = uuid.uuid4().hex[:8]
    password = "Passw0rd123"
    alice = {"username": f"smoke_a_{suffix}", "email": f"smoke_a_{suffix}@example.com", "displayName": f"Alice {suffix}"}
    bob = {"username": f"smoke_b_{suffix}", "email": f"smoke_b_{suffix}@example.com", "displayName": f"Bob {suffix}"}

    print("== Gateway basics")
    status, body, headers = call(gw, "GET", "/api/auth/ping")
    check("auth ping through gateway", status == 200 and body["data"]["service"] == "auth-service", status)
    check("one correlation id header", len(headers.get_all("X-Correlation-Id") or []) == 1)
    status, body, _ = call(gw, "GET", "/api/users/me")
    check("protected route without token is 401 in standard format", status == 401 and body["success"] is False, status)

    print("== Registration and email verification")
    for user in (alice, bob):
        status, body, _ = call(gw, "POST", "/api/auth/register", {**user, "password": password})
        check(f"register {user['username']}", status == 201 and body["data"]["verificationRequired"], f"{status} {body}")
        user["id"] = body["data"]["userId"] if body else None
    status, body, _ = call(gw, "POST", "/api/auth/register", {**alice, "password": password})
    check("duplicate email gives 409 EMAIL_TAKEN", status == 409 and body["data"]["errorCode"] == "EMAIL_TAKEN", status)
    status, body, _ = call(gw, "POST", "/api/auth/login", {"identifier": alice["email"], "password": password})
    check("login before verification is 403 EMAIL_NOT_VERIFIED", status == 403 and body["data"]["errorCode"] == "EMAIL_NOT_VERIFIED", status)

    for user in (alice, bob):
        code = wait_for_mail(args.mailhog, user["email"], r"verification code is: (\d{6})")
        check(f"verification mail arrived for {user['username']}", code is not None)
        status, body, _ = call(gw, "POST", "/api/auth/verify-otp",
                               {"identifier": user["email"], "otp": code or "000000", "purpose": "EMAIL_VERIFY"})
        check(f"verify email {user['username']}", status == 200, f"{status} {body}")

    print("== Login, profile created from Kafka event")
    status, body, _ = call(gw, "POST", "/api/auth/login",
                           {"identifier": alice["email"], "password": password, "deviceName": "Smoke laptop"})
    check("alice login", status == 200 and body["data"]["tokenType"] == "Bearer", status)
    alice_tokens = body["data"]
    status, body, _ = call(gw, "POST", "/api/auth/login", {"identifier": bob["email"], "password": password})
    bob_tokens = body["data"]
    at, bt = alice_tokens["accessToken"], bob_tokens["accessToken"]

    def my_profile():
        s, b, _ = call(gw, "GET", "/api/users/me", token=at)
        return b if s == 200 else None
    profile = wait_for(my_profile)
    check("profile exists (UserRegisteredEvent consumed by user-service)", profile is not None)
    if profile:
        check("profile has the registered data", profile["data"]["username"] == alice["username"]
              and profile["data"]["displayName"] == alice["displayName"])

    print("== Profile, search, contacts, block, privacy")
    status, body, _ = call(gw, "PUT", "/api/users/me", {"displayName": "Alice Updated", "about": "Hello there"}, token=at)
    check("update profile", status == 200 and body["data"]["about"] == "Hello there", f"{status} {body}")

    def bob_visible_in_search():
        s, b, _ = call(gw, "GET", "/api/users/search?q=" + urllib.parse.quote(bob["username"]), token=at)
        return b if s == 200 and any(u["id"] == bob["id"] for u in b["data"]) else None
    wait_for(bob_visible_in_search)
    check("alice finds bob by username", bob_visible_in_search() is not None)
    status, body, _ = call(gw, "GET", "/api/users/search?q=a", token=at)
    check("search with 1 character is 400", status == 400, status)
    status, body, _ = call(gw, "GET", f"/api/users/{bob['id']}", token=at)
    check("view bob's public profile (no email)", status == 200 and "email" not in body["data"], f"{status} {body}")

    status, body, _ = call(gw, "POST", "/api/contacts", {"contactUserId": bob["id"], "nickname": "Bobby"}, token=at)
    check("add bob as contact", status == 201 and body["data"]["user"]["id"] == bob["id"], f"{status} {body}")
    contact_id = body["data"]["id"] if body else None
    status, body, _ = call(gw, "POST", "/api/contacts", {"contactUserId": bob["id"]}, token=at)
    check("adding the same contact again is 409", status == 409 and body["data"]["errorCode"] == "ALREADY_CONTACT", status)
    status, body, _ = call(gw, "GET", "/api/contacts", token=at)
    check("contact list has bob", status == 200 and len(body["data"]) == 1)
    status, body, _ = call(gw, "DELETE", f"/api/contacts/{contact_id}", token=at)
    check("remove contact", status == 200, status)

    status, body, _ = call(gw, "PUT", "/api/users/me/privacy",
                           {"lastSeen": "CONTACTS", "profilePhoto": "NOBODY", "about": "NOBODY",
                            "groupAdd": "CONTACTS", "readReceipts": False}, token=bt)
    check("bob changes privacy", status == 200 and body["data"]["about"] == "NOBODY", f"{status} {body}")
    status, body, _ = call(gw, "GET", f"/api/users/{bob['id']}", token=at)
    check("privacy hides bob's about from alice", status == 200 and body["data"]["about"] is None, f"{status} {body}")

    status, body, _ = call(gw, "POST", f"/api/users/{bob['id']}/block", token=at)
    check("alice blocks bob", status == 200, f"{status} {body}")
    status, body, _ = call(gw, "GET", "/api/users/blocked", token=at)
    check("blocked list has bob", status == 200 and any(u["id"] == bob["id"] for u in body["data"]))
    status, body, _ = call(gw, "GET", f"/api/users/{alice['id']}", token=bt)
    check("bob can no longer see alice (404)", status == 404, status)
    status, body, _ = call(gw, "DELETE", f"/api/users/{bob['id']}/block", token=at)
    check("alice unblocks bob", status == 200, status)

    print("== Conversations and messages")
    status, body, _ = call(gw, "POST", "/api/conversations", {"type": "PRIVATE", "userId": alice["id"]}, token=at)
    check("talking to yourself is 400", status == 400, status)
    status, body, _ = call(gw, "POST", "/api/conversations", {"type": "GROUP", "name": "x", "memberIds": []}, token=at)
    check("a group without other members is 400", status == 400, f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/conversations", {"type": "PRIVATE", "userId": bob["id"]}, token=at)
    check("alice starts a conversation with bob (201)", status == 201 and body["data"]["otherUser"]["id"] == bob["id"], f"{status} {body}")
    conv = body["data"]["id"] if body else None
    status, body, _ = call(gw, "POST", "/api/conversations", {"type": "PRIVATE", "userId": alice["id"]}, token=bt)
    check("bob asking for the same pair gets the SAME conversation (200)", status == 200 and body["data"]["id"] == conv, f"{status} {body}")

    stranger = {"username": f"smoke_c_{suffix}", "email": f"smoke_c_{suffix}@example.com", "displayName": f"Carol {suffix}"}
    call(gw, "POST", "/api/auth/register", {**stranger, "password": password})
    code = wait_for_mail(args.mailhog, stranger["email"], r"verification code is: (\d{6})")
    call(gw, "POST", "/api/auth/verify-otp", {"identifier": stranger["email"], "otp": code or "000000", "purpose": "EMAIL_VERIFY"})
    status, body, _ = call(gw, "POST", "/api/auth/login", {"identifier": stranger["email"], "password": password})
    ct = body["data"]["accessToken"]
    stranger["id"] = body["data"]["user"]["id"]
    status, body, _ = call(gw, "GET", f"/api/conversations/{conv}", token=ct)
    check("a stranger cannot open the conversation (404)", status == 404, status)
    status, body, _ = call(gw, "GET", f"/api/conversations/{conv}/messages", token=ct)
    check("a stranger cannot read its history (404)", status == 404, status)
    status, body, _ = call(gw, "POST", "/api/messages",
                           {"conversationId": conv, "type": "TEXT", "content": "let me in", "idempotencyKey": uuid.uuid4().hex}, token=ct)
    check("a stranger cannot send into it (404)", status == 404, status)

    key = uuid.uuid4().hex
    msg = {"conversationId": conv, "type": "TEXT", "content": "Hello Bob!", "idempotencyKey": key}
    status, body, _ = call(gw, "POST", "/api/messages", msg, token=at)
    check("alice sends a message (201, SENT)", status == 201 and body["data"]["status"] == "SENT", f"{status} {body}")
    m1 = body["data"]["id"] if body else None
    status, body, _ = call(gw, "POST", "/api/messages", msg, token=at)
    check("retrying with the same key returns the same message (200)", status == 200 and body["data"]["id"] == m1, f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/messages", {**msg, "idempotencyKey": uuid.uuid4().hex, "content": "Second one"}, token=at)
    m2 = body["data"]["id"] if body else None

    status, body, _ = call(gw, "GET", f"/api/conversations/{conv}/messages?limit=10", token=bt)
    ids = [m["id"] for m in body["data"]["items"]] if status == 200 else []
    check("history lists both messages, newest first", ids == [m2, m1], f"{status} {ids}")
    check("incoming messages show no receipt yet", all(m["myReceipt"] is None and m["status"] is None for m in body["data"]["items"]))

    def summary(token):
        s, b, _ = call(gw, "GET", "/api/messages/summaries", token=token)
        return next((x for x in (b["data"] if s == 200 else []) if x["conversationId"] == conv), None)
    check("bob has 2 unread messages in the chat list", (summary(bt) or {}).get("unreadCount") == 2)
    check("alice has 0 unread (own messages)", (summary(at) or {}).get("unreadCount") == 0)

    def alice_sees(message_id):
        s, b, _ = call(gw, "GET", f"/api/conversations/{conv}/messages", token=at)
        return next((m["status"] for m in b["data"]["items"] if m["id"] == message_id), None)
    check("nothing is delivered by itself (still SENT)", alice_sees(m1) == "SENT")
    status, body, _ = call(gw, "POST", "/api/messages/delivered", {"messageIds": [m1]}, token=bt)
    check("bob acknowledges delivery", status == 200 and body["data"]["updated"] == 1, f"{status} {body}")
    check("alice now sees DELIVERED", alice_sees(m1) == "DELIVERED")
    status, body, _ = call(gw, "POST", f"/api/conversations/{conv}/read", {"upToMessageId": m2}, token=bt)
    check("bob reads the conversation", status == 200 and body["data"]["updated"] == 2, f"{status} {body}")
    # earlier in this test bob turned read receipts OFF, so alice must still see only DELIVERED
    check("bob's privacy hides READ from alice (she sees DELIVERED)", alice_sees(m1) == "DELIVERED" and alice_sees(m2) == "DELIVERED")
    status, body, _ = call(gw, "PUT", "/api/users/me/privacy",
                           {"lastSeen": "CONTACTS", "profilePhoto": "NOBODY", "about": "NOBODY",
                            "groupAdd": "CONTACTS", "readReceipts": True}, token=bt)
    check("bob turns read receipts back on", status == 200 and body["data"]["readReceipts"] is True, f"{status} {body}")
    check("alice now sees READ", alice_sees(m1) == "READ" and alice_sees(m2) == "READ")
    check("bob's unread count is 0 again", (summary(bt) or {}).get("unreadCount") == 0)
    status, body, _ = call(gw, "POST", f"/api/conversations/{conv}/read", {"upToMessageId": m2}, token=ct)
    check("a stranger cannot mark as read (404)", status == 404, status)

    status, body, _ = call(gw, "GET", "/api/messages/sync", token=bt)
    cursor = body["data"]["nextCursor"] if status == 200 else None
    check("sync without cursor is empty and gives a start cursor", status == 200 and body["data"]["items"] == [] and cursor)
    status, body, _ = call(gw, "POST", "/api/messages",
                           {"conversationId": conv, "type": "TEXT", "content": "While you were away", "idempotencyKey": uuid.uuid4().hex}, token=at)
    m3 = body["data"]["id"] if body else None
    status, body, _ = call(gw, "GET", "/api/messages/sync?after=" + urllib.parse.quote(cursor or ""), token=bt)
    check("bob catches up with sync", status == 200 and m3 in [m["id"] for m in body["data"]["items"]], f"{status} {body}")
    status, body, _ = call(gw, "GET", "/api/messages/sync?after=" + urllib.parse.quote(cursor or ""), token=ct)
    check("a stranger's sync shows nothing of this conversation", status == 200 and body["data"]["items"] == [], f"{status} {body}")

    status, body, _ = call(gw, "POST", "/api/messages", {**msg, "idempotencyKey": uuid.uuid4().hex, "content": ""}, token=at)
    check("empty message is 400", status == 400, status)
    status, body, _ = call(gw, "POST", "/api/messages", {**msg, "idempotencyKey": uuid.uuid4().hex, "content": "x" * 4001}, token=at)
    check("message over 4000 characters is 400", status == 400, status)

    call(gw, "POST", f"/api/users/{bob['id']}/block", token=at)
    status, body, _ = call(gw, "POST", "/api/messages", {**msg, "idempotencyKey": uuid.uuid4().hex, "content": "blocked?"}, token=at)
    check("the blocker cannot message the blocked user (403 MESSAGING_NOT_ALLOWED)", status == 403 and body["data"]["errorCode"] == "MESSAGING_NOT_ALLOWED", f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/messages", {**msg, "idempotencyKey": uuid.uuid4().hex, "content": "blocked?"}, token=bt)
    check("the blocked user cannot message either (403, same answer)", status == 403 and body["data"]["errorCode"] == "MESSAGING_NOT_ALLOWED", f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/conversations", {"type": "PRIVATE", "userId": bob["id"]}, token=at)
    check("and a conversation cannot be (re)opened while blocked (403)", status == 403, f"{status} {body}")
    call(gw, "DELETE", f"/api/users/{bob['id']}/block", token=at)
    status, body, _ = call(gw, "POST", "/api/messages", {**msg, "idempotencyKey": uuid.uuid4().hex, "content": "friends again"}, token=at)
    check("after unblocking, messaging works again", status == 201, f"{status} {body}")

    run_group_checks(gw, alice, bob, stranger, at, bt, ct, conv)
    run_media_checks(gw, alice, bob, stranger, at, bt, ct, conv)
    run_presence_checks(gw, alice, bob, stranger, at, bt, ct)
    run_notification_checks(gw, alice, bob, stranger, at, bt, ct, conv)
    run_call_checks(gw, alice, bob, stranger, at, bt, ct, password)
    run_status_checks(gw, alice, bob, stranger, at, bt, ct)

    print("== Sessions, refresh rotation, logout")
    status, body, _ = call(gw, "GET", "/api/auth/sessions", token=at)
    check("sessions list shows the current device",
          status == 200 and any(s["current"] and s["deviceName"] == "Smoke laptop" for s in body["data"]), f"{status} {body}")

    status, body, _ = call(gw, "POST", "/api/auth/refresh", {"refreshToken": alice_tokens["refreshToken"]})
    check("refresh gives a new token pair", status == 200 and body["data"]["refreshToken"] != alice_tokens["refreshToken"], status)
    new_tokens = body["data"]
    status, body, _ = call(gw, "POST", "/api/auth/refresh", {"refreshToken": alice_tokens["refreshToken"]})
    check("reusing the old refresh token is 401", status == 401, status)
    status, body, _ = call(gw, "POST", "/api/auth/refresh", {"refreshToken": new_tokens["refreshToken"]})
    check("...and it ended the whole session", status == 401, status)
    status, body, _ = call(gw, "GET", "/api/users/me", token=new_tokens["accessToken"])
    check("access token of the ended session is rejected by the gateway", status == 401, status)

    status, body, _ = call(gw, "POST", "/api/auth/login", {"identifier": bob["email"], "password": password})
    session_tokens = body["data"]
    status, body, _ = call(gw, "POST", "/api/auth/logout", {"refreshToken": session_tokens["refreshToken"]})
    check("logout", status == 200, status)
    status, body, _ = call(gw, "GET", "/api/users/me", token=session_tokens["accessToken"])
    check("token is rejected right after logout", status == 401, status)

    print("== Password reset")
    status, body, _ = call(gw, "POST", "/api/auth/forgot-password", {"identifier": alice["email"]})
    check("forgot password", status == 200, status)
    token = wait_for_mail(args.mailhog, alice["email"], r"reset-password\?token=([A-Za-z0-9_-]+)")
    check("reset mail arrived", token is not None)
    status, body, _ = call(gw, "POST", "/api/auth/reset-password", {"token": token or "x", "newPassword": "NewPassw0rd9"})
    check("reset password", status == 200, f"{status} {body}")
    status, body, _ = call(gw, "POST", "/api/auth/login", {"identifier": alice["email"], "password": password})
    check("old password no longer works", status == 401, status)
    status, body, _ = call(gw, "POST", "/api/auth/login", {"identifier": alice["email"], "password": "NewPassw0rd9"})
    check("new password works", status == 200, status)

    print("== Rate limiting")
    codes = []
    ip = {"X-Forwarded-For": "10.250." + str(int(suffix[:2], 16)) + "." + str(int(suffix[2:4], 16))}
    for _ in range(40):
        status, _, _ = call(gw, "GET", "/api/auth/ping", headers=ip)
        codes.append(status)
    check("a burst of 40 requests from one IP gets 429 at some point", 429 in codes, f"statuses seen: {sorted(set(codes))}")

    print()
    print(f"{checks - failures}/{checks} checks passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
