# NoteArray — DevOps Deployment & Operations Checklist

Deployment guide for a **fresh Ubuntu server**: PostgreSQL, Java 25, the Quarkus fast-jar, systemd,
nginx + Let's Encrypt TLS, backups and day-2 operations.

> **Scope / architecture:** one Linux host runs everything. The Quarkus application serves **both** the
> REST API and the built UI on a single HTTP port (default `8080`) and stores **all** data — note text,
> tags, users, sessions, and attachment blobs (`BYTEA`) — in PostgreSQL. TLS is terminated by nginx;
> the app listens on `127.0.0.1` only. There is no Redis/queue/object store to install.
>
> **Target:** Ubuntu 24.04 LTS or 26.04 LTS, x86_64 or arm64. Root/sudo access. Replace placeholders:
> `<DNS_NAME>` (e.g. `notes.example.com`), `<DB_PASSWORD>`, `<OPS_EMAIL>`.
>
> **Verified:** the commands marked ✅ were executed against a real production-mode run while writing this
> guide (prod profile, health checks, CSRF/host matrix, cookie flags, migration validation, smoke tests).

---

## 0. Day-1 checklist

- [ ] DNS `A`/`AAAA` record for `<DNS_NAME>` pointing at the host, propagated (`dig +short <DNS_NAME>`)
- [ ] Host baseline: updates, timezone, NTP, dedicated service user, directories, firewall (§1)
- [ ] **Java 25** installed and `java -version` reports 25 (§2)
- [ ] **PostgreSQL** installed, role + database created, `max_connections` ≥ 100 (§3)
- [ ] Application artifact built and deployed to `/opt/notearray/current` (§4)
- [ ] `/etc/notearray/notearray.env` written with DB credentials + host validation, mode `0600` (§5)
- [ ] `notearray.service` installed, `systemctl enable --now notearray`, health check green (§6 + §8)
- [ ] nginx site installed, `nginx -t` clean (§7)
- [ ] **Let's Encrypt** certificate issued, HTTP→HTTPS redirect working, renewal timer enabled (§7)
- [ ] Smoke tests pass (§8) — especially the **Host/Origin matrix** (proxy misconfiguration is the #1 day-one failure)
- [ ] Backup timer installed **and a restore tested** (§9.1)
- [ ] Certificate expiry monitoring in place (§9.5)
- [ ] `/q/*` restricted at nginx except health; disk-space alerting on the DB volume (§7, §9.4)

---

## 1. Host baseline

```bash
sudo apt update && sudo apt -y upgrade
sudo apt -y install curl wget gnupg ca-certificates git unzip ufw fail2ban unattended-upgrades

# Correct time matters: session expiry, TLS, and rate-limit windows all depend on it.
sudo timedatectl set-timezone Etc/UTC
sudo systemctl enable --now systemd-timesyncd
timedatectl status | grep -E "System clock|NTP service"        # NTP service: active

# Dedicated unprivileged user — the app never needs root and writes nothing to disk.
sudo useradd --system --create-home --home-dir /var/lib/notearray --shell /usr/sbin/nologin notearray
sudo mkdir -p /opt/notearray/releases /etc/notearray /var/backups/notearray
sudo chown -R notearray:notearray /opt/notearray /var/backups/notearray
sudo chmod 750 /etc/notearray

# Firewall: only SSH + HTTP(S). Port 8080 stays closed to the world (nginx → 127.0.0.1).
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw --force enable
sudo ufw status verbose
```

Harden SSH (key-only) if you have not already:

```bash
sudo sed -i 's/^#\?PasswordAuthentication.*/PasswordAuthentication no/' /etc/ssh/sshd_config
sudo sed -i 's/^#\?PermitRootLogin.*/PermitRootLogin no/' /etc/ssh/sshd_config
sudo systemctl reload ssh
```

---

## 2. Java 25 ✅

The application is compiled with `maven.compiler.release=25`, so the runtime **must be JDK 25 or newer**.
Nothing else (no Node, no Maven) is needed *at runtime*.

### Option A — Adoptium Temurin 25 (works on Ubuntu 24.04+)

```bash
sudo mkdir -p /etc/apt/keyrings
wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo gpg --dearmor -o /etc/apt/keyrings/adoptium.gpg
echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb \
$(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt update
sudo apt -y install temurin-25-jdk
```

### Option B — distribution JDK (Ubuntu 25.10 / 26.04 LTS)

```bash
sudo apt -y install openjdk-25-jdk
```

### Option C — vendor tarball (no package repo, pinned version)

```bash
# Download from adoptium.net (or your vendor), then:
sudo mkdir -p /opt/jdk-25 && sudo tar -xzf /tmp/OpenJDK25U-jdk_*_linux_*.tar.gz -C /opt/jdk-25 --strip-components=1
sudo ln -sfn /opt/jdk-25/bin/java /usr/local/bin/java
```

### Verify ✅

```bash
java -version            # must print openjdk version "25..." or newer
readlink -f "$(command -v java)"
```

Runtime JVM flags used by the unit file in §6: `-Xms256m -XX:MaxRAMPercentage=75`. Size the heap for
worst-case concurrency: attachments are handled **in memory** (≤10 MB each) and a JEX import buffers a
≤50 MB archive per running import (max 2 concurrent). **1–2 GB heap** is comfortable for small/medium
tenants; the box should have ≥2 GB RAM plus room for PostgreSQL.

---

## 3. PostgreSQL ✅

```bash
sudo apt -y install postgresql postgresql-contrib
sudo systemctl enable --now postgresql
psql --version            # 16+ is fine (18 tested)
```

Create the role and database. **The app's role must own the database** — Flyway creates/updates tables at
startup, so it needs DDL rights on `public`.

```bash
sudo -u postgres psql <<'SQL'
CREATE ROLE notearray LOGIN PASSWORD '<DB_PASSWORD>';
CREATE DATABASE notearray OWNER notearray ENCODING 'UTF8' TEMPLATE template0;
REVOKE ALL ON DATABASE notearray FROM PUBLIC;
SQL
```

Keep the database off the network unless you really need remote access (nginx and the app are on the same host):

```bash
sudo sed -i "s/^#\?listen_addresses.*/listen_addresses = 'localhost'/" /etc/postgresql/*/main/postgresql.conf
sudo systemctl restart postgresql

# Password auth for local TCP connections (scram-sha-256):
sudo sed -i 's/^\(host\s\+all\s\+all\s\+127\.0\.0\.1\/32\s\+\)\S*/\1scram-sha-256/' /etc/postgresql/*/main/pg_hba.conf
sudo systemctl reload postgresql
```

Connection budget: the app's pool is **50** connections by default (`quarkus.datasource.jdbc.max-size`),
PostgreSQL's default `max_connections` is **100**. That is fine for **one** app instance. If you later run
*N* instances, either lower the pool (`QUARKUS_DATASOURCE_JDBC_MAX_SIZE=$((100/N - reserve))`) or raise
`max_connections`. Verify:

```bash
sudo -u postgres psql -tAc "SHOW max_connections"
PGPASSWORD='<DB_PASSWORD>' psql -h 127.0.0.1 -U notearray -d notearray -c "select current_user, current_database();"   # ✅
```

Optional sizing for a small host (`/etc/postgresql/*/main/postgresql.conf`): `shared_buffers = 25%RAM`,
`effective_cache_size = 50%RAM`, `work_mem = 16MB`, `maintenance_work_mem = 256MB`. Restart afterwards.
Monitor disk: **attachments live in the database**, so the data directory grows with every upload.

---

## 4. Build & deploy the artifact

You need **Node 20+** and **Maven 3.9+** to build; neither is required at runtime.

### 4a. Build (on the server or in CI — CI is preferred)

```bash
git clone <REPO_URL> /tmp/notearray-src && cd /tmp/notearray-src
cd ui && npm ci && npm run build && cd ..     # emits the UI into src/main/resources/META-INF/resources
mvn -DskipTests clean package                 # full test suite: drop -DskipTests
```

Output layout: `target/quarkus-app/` (Quarkus **fast-jar**) containing `quarkus-run.jar`, `lib/`, `app/`, `quarkus/`.
Run it anywhere with `java -jar …/quarkus-run.jar` — the packaged jar runs the **`prod` profile by default** ✅.

### 4b. Install with versioned releases + atomic switch

```bash
VERSION=$(git -C /tmp/notearray-src rev-parse --short HEAD)
sudo mkdir -p /opt/notearray/releases/$VERSION
sudo cp -r /tmp/notearray-src/target/quarkus-app/. /opt/notearray/releases/$VERSION/
sudo chown -R notearray:notearray /opt/notearray
sudo ln -sfn /opt/notearray/releases/$VERSION /opt/notearray/current
ls -l /opt/notearray/current          # → /opt/notearray/releases/<VERSION>
```

Rollback is then a one-liner (see §9.3).

---

## 5. Configuration & secrets

The packaged app **refuses to start without `NOTEARRAY_DB_URL`, `NOTEARRAY_DB_USERNAME` and
`NOTEARRAY_DB_PASSWORD`** (the `%prod` overrides have no defaults — deliberate, so a misconfigured deploy
fails loudly instead of silently pointing at a dev database).

```bash
sudo tee /etc/notearray/notearray.env >/dev/null <<'ENV'
# ---- database (required) ----
NOTEARRAY_DB_URL=jdbc:postgresql://127.0.0.1:5432/notearray
NOTEARRAY_DB_USERNAME=notearray
NOTEARRAY_DB_PASSWORD=<DB_PASSWORD>

# ---- HTTP: bind to loopback only; nginx terminates TLS ----
QUARKUS_HTTP_HOST=127.0.0.1
QUARKUS_HTTP_PORT=8080

# ---- reverse proxy / host header ----
# The app rejects requests whose Host header is not one of these (400) and its CSRF
# filter requires Origin's host to equal the Host header (403). nginx forwards the
# public Host, so list it here together with the loopback names used by health checks.
QUARKUS_HTTP_HOST_VALIDATION_ALLOWED_HOSTS=<DNS_NAME>,localhost,127.0.0.1
# Trust X-Forwarded-For for rate limiting — ONLY behind a proxy that overwrites it.
NOTEARRAY_RATE_LIMIT_TRUST_PROXY=true

# ---- optional tuning ----
# QUARKUS_DATASOURCE_JDBC_MAX_SIZE=50          # DB pool (keep pool x instances < max_connections)
# QUARKUS_THREAD_POOL_MAX_THREADS=256          # blocking-request ceiling (default max(8*cores,200))
# QUARKUS_LOG_LEVEL=INFO
# NOTEARRAY_RATE_LIMIT_LOGIN_PER_HOUR_PER_IP=120
# NOTEARRAY_RATE_LIMIT_SIGNUP_PER_HOUR_PER_IP=30
# NOTEARRAY_ATTACHMENTS_ORPHAN_GRACE=PT24H     # unused-attachment grace before purge
# NOTEARRAY_ATTACHMENTS_PURGE_INTERVAL=1h

# ---- Stripe billing (optional — leave out to disable paid plans; see STRIPE.md) ----
# NOTEARRAY_STRIPE_API_KEY=sk_live_...
# NOTEARRAY_STRIPE_PRO_PRICE_ID=price_...
# NOTEARRAY_STRIPE_PREMIUM_PRICE_ID=price_...
# NOTEARRAY_STRIPE_PRO_CHECKOUT_URL=https://buy.stripe.com/...
# NOTEARRAY_STRIPE_PREMIUM_CHECKOUT_URL=https://buy.stripe.com/...
# NOTEARRAY_STRIPE_WEBHOOK_SECRET=whsec_...
ENV

sudo chown root:notearray /etc/notearray/notearray.env
sudo chmod 640 /etc/notearray/notearray.env     # readable by the service, not world-readable
```

Naming rule: Quarkus maps properties to environment variables mechanically —
`quarkus.datasource.jdbc.max-size` → `QUARKUS_DATASOURCE_JDBC_MAX_SIZE`,
`notearray.rate-limit.trust-proxy` → `NOTEARRAY_RATE_LIMIT_TRUST_PROXY`.

> **Do not** turn off host validation (`QUARKUS_HTTP_HOST_VALIDATION_REQUIRE_LOCALHOST=false`) as a shortcut —
> allow-list the real hostname instead. Host validation is what blocks Host-header attacks.

---

## 6. systemd service

```bash
sudo tee /etc/systemd/system/notearray.service >/dev/null <<'UNIT'
[Unit]
Description=NoteArray (Quarkus notes + attachments API and UI)
Documentation=https://github.com/rcprcp/NoteArray
After=network-online.target postgresql.service
Wants=network-online.target
Requires=postgresql.service

[Service]
Type=simple
User=notearray
Group=notearray
WorkingDirectory=/opt/notearray/current
EnvironmentFile=/etc/notearray/notearray.env
ExecStart=/usr/bin/java -Xms256m -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError \
          -jar /opt/notearray/current/quarkus-run.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
TimeoutStopSec=30

# No shell, no new privileges, read-only filesystem access (all state is in PostgreSQL;
# logs go to the journal).
NoNewPrivileges=true
PrivateTmp=true
PrivateDevices=true
ProtectSystem=strict
ProtectHome=true
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now notearray
systemctl status notearray --no-pager
journalctl -u notearray -f          # watch startup; look for "Profile prod activated" and "Listening on"
```

Startup is fast (~1–2 s) ✅ and **Flyway migrations run automatically at boot** ✅ (validated, then applied if new):

```
Successfully validated 20 migrations … Current version of schema "public": 1.1.9 … up to date. No migration necessary
```

> **Never edit a migration file that has already run.** Flyway validates checksums and will refuse to start.
> Schema changes always arrive as a *new* `V…__*.sql` file. Downgrading the app to a build with fewer
> migrations is not supported by Flyway without a restore (§9.1).

Quick verification without nginx ✅:

```bash
curl -fsS http://127.0.0.1:8080/q/health/ready | head -c 200   # {"status":"UP", ...}
curl -fsS http://127.0.0.1:8080/q/health/live  | head -c 200
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/      # 200 (serves the built UI)
```

---

## 7. nginx + Let's Encrypt

### 7.1 Install

```bash
sudo apt -y install nginx certbot python3-certbot-nginx
sudo systemctl enable --now nginx
```

### 7.2 Site configuration

Two details are **not optional** for this app:

1. **`proxy_set_header Host $host;`** — the app's CSRF filter compares the `Origin` header's host to the
   request's `Host`. Forwarding `127.0.0.1:8080` instead of the public name makes every POST/PUT/DELETE fail
   with `403 Cross-site request blocked`.
2. **`client_max_body_size` ≥ 55m** — the app accepts 50 MB JEX imports and 10 MB attachments
   (`quarkus.http.limits.max-body-size=55M`). nginx's 1 MB default truncates uploads with `413`.

```bash
sudo tee /etc/nginx/sites-available/notearray >/dev/null <<'NGINX'
server {
    listen 80;
    listen [::]:80;
    server_name <DNS_NAME>;

    # Let's Encrypt HTTP-01 challenges stay on port 80 (certbot manages this block).
    location /.well-known/acme-challenge/ { root /var/www/html; }

    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl;
    listen [::]:443 ssl;
    http2 on;
    server_name <DNS_NAME>;

    # certbot --nginx fills these in; keep them if you manage certificates manually.
    ssl_certificate     /etc/letsencrypt/live/<DNS_NAME>/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/<DNS_NAME>/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    # Uploads: 50 MB JEX archives + 10 MB attachments (app limit is 55m).
    client_max_body_size 64m;

    # Do not leak internals; the app already sends CSP, X-Frame-Options, X-Content-Type-Options
    # and HSTS itself (see application.properties), so nginx must not duplicate them.
    server_tokens off;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        # REQUIRED: the app validates Host and matches it against Origin (CSRF).
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Imports can legitimately run for minutes (10k notes, 28 attachments, etc.).
        proxy_connect_timeout 10s;
        proxy_send_timeout    600s;
        proxy_read_timeout    600s;
        proxy_request_buffering on;      # spool big uploads to disk instead of memory
    }

    # Health checks are for the host, not the internet. Block the rest of /q/* too.
    location = /q/health/live  { allow 127.0.0.1; deny all; proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
    location = /q/health/ready { allow 127.0.0.1; deny all; proxy_pass http://127.0.0.1:8080; proxy_set_header Host $host; }
    location /q/               { return 404; }
}
NGINX

sudo ln -sfn /etc/nginx/sites-available/notearray /etc/nginx/sites-enabled/notearray
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

> `/q/swagger-ui`, `/q/openapi` and Prometheus metrics are **disabled in the prod profile** ✅ (they 404 in the
> packaged jar). The `location /q/ { return 404; }` is belt-and-braces for the dev-only endpoints.

### 7.3 Issue the certificate

```bash
sudo certbot --nginx -d <DNS_NAME> --redirect -m <OPS_EMAIL> --agree-tos --no-eff-email
sudo certbot certificates                       # shows the cert, SANs, expiry
sudo certbot renew --dry-run                    # ✅ proves renewal works end-to-end
systemctl list-timers | grep certbot            # renewal timer (twice daily)
```

certbot's nginx plugin reloads nginx after a successful renewal. Add a deploy hook if you need extra actions
(logging, monitoring ping, or feeding certificates to the app when it terminates TLS itself — see §7.5):

```bash
sudo tee /etc/letsencrypt/renewal-hooks/deploy/notearray-reload.sh >/dev/null <<'HOOK'
#!/bin/sh
# Runs after each successful renewal.
systemctl reload nginx
logger -t notearray-deploy-hook "TLS certificate renewed; nginx reloaded"
HOOK
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/notearray-reload.sh
```

### 7.4 HSTS — read before enabling broadly

The app emits `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload` **in the prod
profile**. Consequences: (a) plain-HTTP access to the domain stops working for a year in browsers that
saw the header; (b) `includeSubDomains` covers every subdomain; (c) don't submit to the preload list until
you are certain. If you need a softer start, override the header until the domain is stable (quoted config
segments map to double underscores in environment variables — verified working ✅):

```bash
# in /etc/notearray/notearray.env
QUARKUS_HTTP_HEADER__STRICT_TRANSPORT_SECURITY__VALUE=max-age=300
```

### 7.5 Alternative: terminate TLS in Quarkus (no nginx)

Possible when you want a single process. Point Quarkus at the Let's Encrypt PEMs and let it redirect:

```bash
# /etc/notearray/notearray.env
QUARKUS_HTTP_HOST=0.0.0.0
QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/etc/letsencrypt/live/<DNS_NAME>/fullchain.pem
QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/etc/letsencrypt/live/<DNS_NAME>/privkey.pem
QUARKUS_HTTP_INSECURE_REQUESTS=redirect
```

`/etc/letsencrypt/live` is only readable by root, so grant the service group read access (or copy the PEMs
into `/etc/notearray/tls` in a certbot deploy hook) and make the deploy hook restart the app:

```bash
sudo tee /etc/letsencrypt/renewal-hooks/deploy/notearray-app.sh >/dev/null <<'HOOK'
#!/bin/sh
install -o root -g notearray -m 0640 /etc/letsencrypt/live/<DNS_NAME>/fullchain.pem /etc/notearray/tls/fullchain.pem
install -o root -g notearray -m 0640 /etc/letsencrypt/live/<DNS_NAME>/privkey.pem   /etc/notearray/tls/privkey.pem
systemctl restart notearray
HOOK
```

If you take this route, open `443` to the app port and remember host validation still applies:
`QUARKUS_HTTP_HOST_VALIDATION_ALLOWED_HOSTS=<DNS_NAME>`.

---

## 8. Post-deploy smoke tests ✅

Run from the host; `<DNS_NAME>` goes through nginx (TLS), the loopback checks bypass it.

```bash
# 1. TLS + redirect
curl -sI  http://<DNS_NAME>  | head -1                       # 301 → https
curl -sI  https://<DNS_NAME> | head -1                       # 200
curl -sI  https://<DNS_NAME> | grep -iE "strict-transport|content-security|x-frame" | head -4

# 2. Health (direct and through nginx)
curl -fsS http://127.0.0.1:8080/q/health/ready >/dev/null && echo "ready OK"
curl -fsS https://<DNS_NAME>/q/health/ready -o /dev/null -w 'via nginx: %{http_code}\n'   # 403 from the internet is expected

# 3. UI is served
curl -s https://<DNS_NAME>/ | grep -o '<title>[^<]*</title>' | head -1
curl -s https://<DNS_NAME>/ | grep -o 'assets/index-[^"]*\.js' | head -1     # bundle referenced

# 4. Signup / login / cookie flags
curl -s -o /dev/null -w 'signup: %{http_code}\n' -X POST https://<DNS_NAME>/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"ops-smoke","email":"ops-smoke@example.com","password":"Smoke123!"}'

curl -si -c /tmp/ops.jar -X POST https://<DNS_NAME>/api/users/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"ops-smoke@example.com","password":"Smoke123!"}' \
  | grep -iE '^HTTP|^set-cookie'        # Set-Cookie must contain HttpOnly; SameSite=Strict; Secure

curl -s -b /tmp/ops.jar https://<DNS_NAME>/api/users/me | head -c 120
curl -s -b /tmp/ops.jar https://<DNS_NAME>/api/notes/storage          # {"usedBytes":0,...,"limitBytes":104857600,...}

# 5. Create a note, then upload a small attachment (10 MB limit)
NOTE_ID=$(curl -s -b /tmp/ops.jar -X POST https://<DNS_NAME>/api/notes -H 'Content-Type: application/json' \
  -d '{"title":"ops check","content":"deployed","tags":"ops"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
printf '\x89PNG\r\n\x1a\n' > /tmp/tiny.png
curl -s -b /tmp/ops.jar -X POST "https://<DNS_NAME>/api/notes/$NOTE_ID/attachments?filename=tiny.png" \
  -H 'Content-Type: image/png' --data-binary @/tmp/tiny.png -o /dev/null -w 'upload: %{http_code}\n'   # 201

# 6. Large-body path (55 MB app limit — proves nginx client_max_body_size is right)
head -c 60000000 /dev/zero > /tmp/big.bin
curl -s -o /dev/null -w 'large upload: %{http_code}\n' -b /tmp/ops.jar \
  -X POST "https://<DNS_NAME>/api/notes/$NOTE_ID/attachments?filename=big.png" \
  -H 'Content-Type: image/png' --data-binary @/tmp/big.bin     # 413 is CORRECT (10 MB attachment cap), 413 from nginx would mean the limit is too low

# 7. Clean up the smoke user
UID_TO_DELETE=$(curl -s -b /tmp/ops.jar https://<DNS_NAME>/api/users/me | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
curl -s -o /dev/null -w 'delete user: %{http_code}\n' -b /tmp/ops.jar -X DELETE https://<DNS_NAME>/api/users/$UID_TO_DELETE \
  -H 'Content-Type: application/json' -d '{"currentPassword":"Smoke123!"}'      # 204
```

### 8.1 Proxy behaviour you must get right (verified matrix)

The app enforces two independent checks on state-changing requests. Both matter behind a proxy:

| `Host` header | `Origin` header | Result | Meaning |
|---|---|---|---|
| `notes.example.com` | `https://notes.example.com` | **201** ✅ | Correct nginx config (`Host $host`) |
| `notes.example.com` | `https://evil.example.com` | **403** ✅ | CSRF filter blocked the cross-site origin |
| `evil.example.com` | `https://evil.example.com` | **400** ✅ | Host validation rejected an unknown host |
| `127.0.0.1:8090` | (none) | 201 ✅ | CLI/curl without Origin is allowed |

Consequences to remember:

* If nginx forwards `Host 127.0.0.1:8080`, browser POSTs fail with **403** (`Cross-site request blocked`).
* If the public hostname is **not** in `QUARKUS_HTTP_HOST_VALIDATION_ALLOWED_HOSTS`, every proxied request
  fails with a **bare 400** (logged without an `X-Request-Id`).
* State-changing requests must send `Content-Type: application/json` (or `application/octet-stream` for
  imports) — HTML form posts are rejected by design.

---

## 9. Operations runbook

### 9.1 Backups (and a restore you have actually tested)

Everything — notes **and attachment blobs** — is in PostgreSQL, so a database dump is a complete backup.

```bash
sudo tee /usr/local/bin/notearray-backup.sh >/dev/null <<'SCRIPT'
#!/bin/bash
set -euo pipefail
DEST=/var/backups/notearray
STAMP=$(date +%Y%m%d-%H%M%S)
export PGPASSWORD="$(grep -oP '(?<=^NOTEARRAY_DB_PASSWORD=).*' /etc/notearray/notearray.env)"
pg_dump -h 127.0.0.1 -U notearray -d notearray -Fc -Z6 -f "$DEST/notearray-$STAMP.dump"
# keep 14 daily dumps
find "$DEST" -name 'notearray-*.dump' -type f -mtime +14 -delete
echo "$(date -Is) backup ok: $DEST/notearray-$STAMP.dump ($(du -h "$DEST/notearray-$STAMP.dump" | cut -f1))"
SCRIPT
sudo chmod 750 /usr/local/bin/notearray-backup.sh

sudo tee /etc/systemd/system/notearray-backup.service >/dev/null <<'UNIT'
[Unit]
Description=NoteArray PostgreSQL backup
[Service]
Type=oneshot
User=notearray
ExecStart=/usr/local/bin/notearray-backup.sh
UNIT

sudo tee /etc/systemd/system/notearray-backup.timer >/dev/null <<'UNIT'
[Unit]
Description=Nightly NoteArray backup
[Timer]
OnCalendar=*-*-* 02:30:00
Persistent=true
[Install]
WantedBy=timers.target
UNIT

sudo systemctl daemon-reload && sudo systemctl enable --now notearray-backup.timer
sudo systemctl start notearray-backup.service && journalctl -u notearray-backup -n 5 --no-pager
```

Restore drill (do this once on a **spare** database, not in production):

```bash
export DB_PASSWORD='<DB_PASSWORD>'      # or read it out of /etc/notearray/notearray.env
sudo -u postgres psql -c 'CREATE DATABASE notearray_restore OWNER notearray;'
PGPASSWORD="$DB_PASSWORD" pg_restore -h 127.0.0.1 -U notearray -d notearray_restore --clean --if-exists /var/backups/notearray/notearray-<STAMP>.dump
PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U notearray -d notearray_restore -c 'select count(*) from notes;'
sudo -u postgres psql -c 'DROP DATABASE notearray_restore;'
```

Copy dumps **off the host** (object storage, `rsync` to another machine; encrypt them — attachment blobs can
contain personal or business documents). For point-in-time recovery, enable WAL archiving
(`archive_mode=on`, `archive_command`) and keep base backups — the nightly dump only gets you back to 02:30.

### 9.2 Logs & retention

The app logs to the journal (INFO, access log with `X-Request-Id` and latency). Cap journald growth:

```bash
sudo sed -i 's/^#\?SystemMaxUse=.*/SystemMaxUse=1G/' /etc/systemd/journald.conf
sudo systemctl restart systemd-journald
journalctl -u notearray --since "1 hour ago" | tail -50
journalctl -u notearray -p warning -S today            # warnings/errors only
```

Each request carries an `X-Request-Id` (visible in the access log and echoed to the client) — ask users to
quote it when reporting a problem.

### 9.3 Deploying an update / rollback

```bash
# build (CI or host) as in §4, then:
VERSION=$(git -C /tmp/notearray-src rev-parse --short HEAD)
sudo mkdir -p /opt/notearray/releases/$VERSION
sudo cp -r /tmp/notearray-src/target/quarkus-app/. /opt/notearray/releases/$VERSION/
sudo chown -R notearray:notearray /opt/notearray
sudo ln -sfn /opt/notearray/releases/$VERSION /opt/notearray/current
sudo systemctl restart notearray
sleep 5 && curl -fsS http://127.0.0.1:8080/q/health/ready >/dev/null && echo "deploy OK"

# rollback: point current at the previous release directory and restart
sudo ln -sfn /opt/notearray/releases/<PREVIOUS_VERSION> /opt/notearray/current
sudo systemctl restart notearray
```

Notes: the restart is a few seconds of downtime (single instance); sessions live in PostgreSQL, so users
**stay logged in** across restarts. New Flyway migrations run on boot and are not automatically reversible —
if a release contains schema changes, roll back with a database restore (§9.1), not just the symlink.

### 9.4 Monitoring

```bash
# systemd knows when the service dies
systemctl is-active notearray
# liveness vs readiness (readiness includes a database check)
curl -fsS http://127.0.0.1:8080/q/health/live  >/dev/null || echo "LIVENESS FAILED"
curl -fsS http://127.0.0.1:8080/q/health/ready >/dev/null || echo "READINESS FAILED (db?)"
```

Minimum alert set: service down (`systemctl is-active`), readiness failing, certificate expiry (§9.5),
disk usage on the PostgreSQL data volume (attachments grow it), and backup timer failures
(`systemctl list-timers`, journal for `notearray-backup`). Prometheus metrics are disabled in prod; if you
want them, enable `quarkus.micrometer.export.prometheus.enabled=true` (then expose `/q/metrics` only to your
scraper) and keep `/q/` blocked publicly in nginx.

Two scheduled jobs run inside the app: expired-session cleanup (hourly) and unused-attachment purge (hourly,
24 h grace). With multiple instances each would run them — harmless but wasteful; prefer one instance or
disable the extra jobs there.

### 9.5 Certificate lifecycle

```bash
certbot certificates                  # expiry date + domains
certbot renew --dry-run               # non-destructive renewal test
systemctl list-timers | grep certbot   # should fire twice daily
```

Alert at **30/14/7 days** before expiry (e.g. a cron job parsing `certbot certificates`, or an external
TLS monitor). Remember the app sends HSTS: if the certificate lapses, browsers that previously saw the
header will keep forcing HTTPS and users get hard failures — renewal monitoring is not optional.

### 9.6 Bootstrap a superuser

Superusers see and manage **all** users' notes. Grant it deliberately:

```bash
PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U notearray -d notearray \
  -c "UPDATE users SET superuser = true WHERE email = 'you@example.com';"     # requires an existing account
# revoke:
PGPASSWORD="$DB_PASSWORD" psql -h 127.0.0.1 -U notearray -d notearray \
  -c "UPDATE users SET superuser = false WHERE email = 'you@example.com';"
```

### 9.7 Capacity & tuning knobs

| Knob | Default | Notes |
|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_MAX_SIZE` | 50 | Keep `pool × instances < PostgreSQL max_connections` |
| `QUARKUS_DATASOURCE_JDBC_ACQUISITION_TIMEOUT` | 5s | Requests wait this long for a connection, then fail — raise with the pool |
| `QUARKUS_THREAD_POOL_MAX_THREADS` | `max(8 × cores, 200)` | Blocking-request ceiling; queue is unbounded (overload = latency, then 5xx) |
| `QUARKUS_HTTP_LIMITS_MAX_CONNECTIONS` | unlimited | Optional hard socket cap |
| `QUARKUS_HTTP_LIMITS_MAX_BODY_SIZE` | 55M (set) | Must stay ≥ the largest import/attachment |
| `NOTEARRAY_IMPORT_*` | 50 MB / 256 MB / 10k entries | Import bounds; imports also limited to **2 concurrent** (503 + `Retry-After`) |
| Attachments | 10 MB per file | Code constant (`StorageQuota.MAX_ATTACHMENT_BYTES`) — needs a rebuild to change |
| `NOTEARRAY_RATE_LIMIT_*` | 120 login / 30 signup per IP/hour | Requires `trust-proxy=true` behind nginx |

Measured on a 32-core host (JVM mode, local PostgreSQL): 800 concurrent requests completed in ~230 ms with
all `200`s; the practical ceiling is the DB pool, not the thread pools. Native images (`-Dnative`) are
possible for arm64 but must be **built on/for that architecture** (GraalVM does not cross-compile) — see the
README/native notes before choosing that path.

---

## 10. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Every browser POST/PUT/DELETE → **403 "Cross-site request blocked"** | nginx not forwarding the public `Host` (app compares `Origin` host to `Host`) | add `proxy_set_header Host $host;` (§7.2) |
| Every proxied request → **bare 400**, no `X-Request-Id` in the log | public hostname missing from host validation (default requires localhost when bound to 127.0.0.1) | `QUARKUS_HTTP_HOST_VALIDATION_ALLOWED_HOSTS=<DNS_NAME>,localhost,127.0.0.1` (§5) |
| App exits immediately at startup | `NOTEARRAY_DB_URL/USERNAME/PASSWORD` unset (prod has no defaults) | fill `/etc/notearray/notearray.env`, `systemctl restart notearray` |
| `Flyway validate failed … checksum mismatch` | an already-applied migration file was edited | revert the file; ship schema changes as a new `V…__*.sql` |
| Login "succeeds" then immediately logged out | Secure cookie not stored (plain HTTP) or clock skew | use HTTPS (prod sets `cookie-secure=true`); check `timedatectl` |
| **413** on import/upload | nginx `client_max_body_size` too small | set `client_max_body_size 64m;` and reload nginx |
| **503 "A note import is already running"** | more than 2 concurrent imports (by design) | retry later (`Retry-After: 60`) |
| **504** during a big import | nginx `proxy_read_timeout` too low | raise to `600s` (§7.2) |
| 5xx under load, log shows JDBC connection acquisition timeout | DB pool exhausted (50) | raise `QUARKUS_DATASOURCE_JDBC_MAX_SIZE` (and PostgreSQL `max_connections`), or add capacity |
| UI loads but API calls fail | mixing origins (UI on another vhost) or CSP/CORS assumptions violated | serve UI and API from the same hostname (§7.2) |
| Attachments disappear after editing a note | expected behaviour | links removed → file is orphaned and purged after 24 h (grace); a file still linked elsewhere is kept |
| Disk filling up | attachment blobs live in PostgreSQL | monitor the data volume, raise storage, review large imports; plan quotas are app-level only |

---

## 11. Security checklist

- [ ] App bound to `127.0.0.1`; only nginx exposed (ufw: 22/80/443)
- [ ] PostgreSQL listening on localhost only, `scram-sha-256`, no `PUBLIC` privileges on the app database
- [ ] Secrets in `/etc/notearray/notearray.env` (`0640 root:notearray`), **never** in the jar or git
- [ ] TLS with auto-renewal verified (`certbot renew --dry-run`) and expiry alerting
- [ ] `QUARKUS_HTTP_HOST_VALIDATION_ALLOWED_HOSTS` set to the real hostname (do not disable host validation)
- [ ] `NOTEARRAY_RATE_LIMIT_TRUST_PROXY=true` **only** behind the reverse proxy that overwrites `X-Forwarded-For`
- [ ] `/q/*` blocked publicly except the health endpoints (allowed from localhost)
- [ ] SSH key-only, root login disabled, fail2ban active, `unattended-upgrades` enabled
- [ ] Backups encrypted, copied off-host, and a restore has been rehearsed
- [ ] Superuser accounts reviewed and minimal
- [ ] Optional Stripe: webhook secret set, endpoint `https://<DNS_NAME>/api/stripe/webhook` configured in the
      Stripe dashboard (see `STRIPE.md`)

---

## 12. Appendix — one-shot install transcript

For a fresh host, the numbered sections in order are:

```bash
# §1 host baseline + §2 Java 25 + §3 PostgreSQL + §7 nginx/certbot
# §4 build & install the jar into /opt/notearray/releases/<version> (+ current symlink)
# §5 env file  →  §6 systemd unit  →  §7.2/7.3 nginx + certificate
# §8 smoke tests  →  §9.1 backups + timer  →  §9.4 monitoring
```

Useful one-liners:

```bash
systemctl restart notearray && journalctl -u notearray -f          # restart + follow logs
curl -fsS http://127.0.0.1:8080/q/health/ready                     # readiness (includes DB)
sudo -u postgres psql -d notearray -c "\dt"                        # tables (Flyway-managed)
sudo -u postgres psql -d notearray -c "select version from flyway_schema_history order by installed_rank desc limit 1;"
du -sh /var/lib/postgresql/*/main                                  # data volume size (attachments!)
sudo -u postgres psql -d notearray -c "select count(*) notes, pg_size_pretty(pg_total_relation_size('notes')) from notes;"
```

Related docs in this repository: `README.md` (features, API, production build), `USER_GUIDE.md` (end-user
behaviour), `STRIPE.md` (billing setup), `REVIEW.md` (security review notes).
