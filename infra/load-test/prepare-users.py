#!/usr/bin/env python3
"""
Creates the test data for the load test: N verified users, paired up, every pair with contacts and a private
conversation. Writes infra/load-test/data/users.json (with fresh access tokens, valid for 15 minutes: run the
load test right afterwards). Standard library only.

    python infra/load-test/prepare-users.py              # 40 users = 20 pairs
    python infra/load-test/prepare-users.py --pairs 50 --gateway http://localhost:8200

It uses the gateway like a real client, so it is slow on purpose: /api/auth/** is limited to about 2 calls per second
per IP. 20 pairs need roughly 2 minutes. Never run this against production: it creates real accounts.
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def call(base, method, path, body=None, token=None):
    for attempt in range(8):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(base + path, data=data, method=method)
        request.add_header("Content-Type", "application/json")
        if token:
            request.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                raw = response.read().decode()
                return response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as error:
            raw = error.read().decode()
            if error.code == 429:        # rate limited: wait and try again
                time.sleep(1.5)
                continue
            return error.code, (json.loads(raw) if raw and raw.startswith("{") else None)
    return 429, None


def otp_from_mailhog(mailhog, email):
    for _ in range(40):
        url = mailhog + "/api/v2/search?kind=to&query=" + urllib.parse.quote(email)
        with urllib.request.urlopen(url, timeout=10) as response:
            items = json.loads(response.read().decode()).get("items", [])
        for item in items:
            match = re.search(r"verification code is: (\d{6})", item["Content"]["Body"])
            if match:
                return match.group(1)
        time.sleep(0.5)
    raise RuntimeError("no verification mail for " + email)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default="http://localhost:8200")
    parser.add_argument("--mailhog", default="http://localhost:8025")
    parser.add_argument("--pairs", type=int, default=20)
    args = parser.parse_args()
    gw, password, run = args.gateway, "Passw0rd123", uuid.uuid4().hex[:6]

    users = []
    for index in range(args.pairs * 2):
        name = f"load_{run}_{index:03d}"
        email = f"{name}@example.com"
        status, body = call(gw, "POST", "/api/auth/register", {"username": name, "email": email, "password": password, "displayName": f"Load {index:03d}"})
        assert status == 201, f"register {status} {body}"
        user_id = body["data"]["userId"]
        status, _ = call(gw, "POST", "/api/auth/verify-otp", {"identifier": email, "otp": otp_from_mailhog(args.mailhog, email), "purpose": "EMAIL_VERIFY"})
        assert status == 200, f"verify {status}"
        users.append({"id": user_id, "email": email, "name": name})
        print(f"  user {index + 1}/{args.pairs * 2}", end="\r")
    print()

    # log everybody in as late as possible: the access tokens only live 15 minutes
    for user in users:
        status, body = call(gw, "POST", "/api/auth/login", {"identifier": user["email"], "password": password})
        assert status == 200, f"login {status}"
        user["token"] = body["data"]["accessToken"]

    pairs = []
    for index in range(0, len(users), 2):
        a, b = users[index], users[index + 1]
        for owner, other in ((a, b), (b, a)):
            for attempt in range(30):   # the other profile is created from a Kafka event: wait for it
                status, _ = call(gw, "POST", "/api/contacts", {"contactUserId": other["id"]}, token=owner["token"])
                if status in (201, 409):
                    break
                time.sleep(1)
        status, body = call(gw, "POST", "/api/conversations", {"type": "PRIVATE", "userId": b["id"]}, token=a["token"])
        assert status in (200, 201), f"conversation {status} {body}"
        pairs.append({"a": a, "b": b, "conversationId": body["data"]["id"]})

    out_dir = os.path.join(os.path.dirname(__file__), "data")
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, "users.json"), "w", encoding="utf-8") as handle:
        json.dump({"createdAt": int(time.time()), "pairs": pairs}, handle)
    print(f"Wrote {len(pairs)} pairs to infra/load-test/data/users.json (tokens valid for 15 minutes)")


if __name__ == "__main__":
    sys.exit(main())
