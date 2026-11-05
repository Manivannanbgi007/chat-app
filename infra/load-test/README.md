# Load test (k6)

Measures how fast chat-app is and how much it can take, with real HTTP calls and real WebSocket connections through the
gateway. The number that matters most for a chat is **`chat_delivery_ms`**: the time from "A sends" to "it arrives on B's
WebSocket".

```bash
python infra/load-test/prepare-users.py --pairs 50      # creates 100 verified users + a chat per pair (about 2.5 minutes)
PAIRS=25 BROWSERS=25 bash infra/load-test/run.sh load    # run it within 13 minutes: the access tokens last 15
```
Needs the stack running and Docker (k6 runs in a container, nothing to install). **Never run it against a production system**: it creates real
accounts and thousands of messages.

| Profile | What it does |
|---|---|
| `baseline` | 1 pair + 1 browsing user for 30 s: the speed of an idle system |
| `load` (default) | ramps up to `PAIRS` chatting pairs and `BROWSERS` browsing users, holds 90 s, ramps down |
| `stress` | twice as many users; add `SEND_MS=300` to make every sender post a message every 0.3-0.6 s |

*Chat user* (one per pair): B keeps a WebSocket open (20 s per session, then reconnects), A posts a message every `SEND_MS`-`2*SEND_MS` ms,
then B reads the history. *Browsing user*: profile, chat list, status feed, unread count, presence, contacts, heartbeat, with pauses.

Every virtual user needs **its own pair** (two users on one pair would see each other's messages and spoil the numbers): `PAIRS + BROWSERS`
pairs for `load`, twice that for `stress`. The script stops with a clear message if `data/users.json` has too few.

**Thresholds** (the run exits with code 99 when one is crossed): fewer than 1 % failed requests, send p95 < 500 ms, read p95 < 500 ms,
delivery p95 < 1000 ms, WebSocket connects > 99 %.

**Results of this project** (laptop, Docker Desktop, 25 containers): see `DEPLOYMENT_GUIDE.md` section 9. Watch Grafana (`chat-app overview`)
while a test runs. The summary of every run is also saved in `results/` (git-ignored).

**What the first stress run found:** 8.5 % failed requests, all HTTP 503 from the gateway. Cause: a library default of 25 simultaneous requests per
route (the "bulkhead"). Fixed (limit 500), the same run then had 0 failures (SECURITY_DESIGN.md 7i, finding 6).

Files: `prepare-users.py` (test data), `chat-load.js` (the k6 scenario), `run.sh` (starts k6 in Docker).
