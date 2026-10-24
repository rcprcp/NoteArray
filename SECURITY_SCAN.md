# Security Scan — NoteArray on `192.168.0.2:8080`

- **Date:** 2026-10-24
- **Target:** `192.168.0.2:8080` (packaged jar, `prod` profile)
- **Scanned from:** `192.168.0.146` (same LAN)
- **Method:** non-intrusive — common-port check, HTTP header/endpoint probing, one
  SSH banner grab, and source review of `SecurityHeadersFilter`, `CsrfFilter`,
  `SessionService`, and `AttachmentResource`. No brute-forcing, fuzzing, or DoS.
  **No authenticated testing** (no accounts created; production data untouched).

> Scope note: this is a light LAN assessment, not a full penetration test. It does
> not cover a full 65k port scan, authenticated/application-logic abuse, dependency
> CVEs, or TLS (there was no TLS listener to assess).

## Verdict

The application is solidly built. Authentication, CSRF, security headers, cookie
flags, and rate limiting are all correctly implemented. The material issues were
**deployment**-level, not application-logic bugs.

## Passed ✅

| Check | Result |
|---|---|
| Prod profile active | Yes — HSTS present; `/q/openapi`, `/q/swagger-ui`, `/q/metrics` all `404` |
| Authentication enforced | `/api/notes`, `/api/users`, `/api/attachments/{uuid}` → `401` unauthenticated |
| Object-level access | Non-existent / non-owned attachment → `404` (no existence disclosure) |
| CSRF | `POST` with `Origin: https://evil.example` → `403`; same-origin passes |
| CORS | No `Access-Control-Allow-Origin` reflection for foreign origins |
| Session cookie | `HttpOnly; SameSite=Strict` + `Secure` (prod) |
| Path traversal | `..` and `..%2f` probes → `404`; no directory listing; no `.js.map` source maps |
| Methods / banner | `TRACE` → `405`; no `Server` version header |
| Rate limiting | login 120/hr/IP, signup 30/hr/IP, per-account backoff + lockout |
| Open ports | only `22` (SSH) and `8080` |

## Findings

| # | Severity | Finding | Status |
|---|---|---|---|
| F1 | Medium | CSP and `X-Frame-Options` missing on the static SPA document (`/`, `/index.html`) — the JAX-RS filter never runs for static resources | **Fixed** — headers moved to global `quarkus.http.header.*` |
| F2 | Medium | Plaintext HTTP on `8080` (no TLS listener); credentials/cookies in the clear on the LAN | **Pending** — nginx + Let's Encrypt |
| F3 | Low | Host-header validation not active (`Host: evil.example` accepted, `200`) | **Pending** — enable once a proxy is in front |
| F4 | Low | `/q/health` exposed unauthenticated, reporting DB connectivity | **Fixed** — disabled in the prod profile |
| F5 | Low | Duplicate security headers on REST responses (config + filter both set them) | **Fixed** — consolidated into config only |
| F6 | Info | `SSH (22)` open, advertising `OpenSSH_10.0p2 Debian-7+deb13u4` | **Pending** — confirm key-only (host action) |
| F7 | Info | `index.html` served `cache-control: public, immutable, max-age=86400` | Open (correctness, not security) |

### F1 — CSP / anti-framing missing on the document (fixed)

`SecurityHeadersFilter` was a **JAX-RS** `ContainerResponseFilter`, so it only ran
for REST endpoints. Static resources (`/`, `/index.html`, `/assets/*`) bypassed it
and shipped with no `Content-Security-Policy` and no `X-Frame-Options` — exactly
the document those headers exist to protect.

Fix: all six security headers are now defined once, globally, in
`application.properties` via `quarkus.http.header.*`, and the JAX-RS filter no
longer sets them (which also resolved F5). `frame-ancestors 'self'` /
`X-Frame-Options: SAMEORIGIN` is deliberate — the attachment preview modal embeds
files in a same-origin `<iframe>` (`ui/src/NotesPanel.jsx`); cross-site framing,
i.e. clickjacking, remains blocked.

### F2 — Plaintext HTTP (pending)

`openssl s_client -connect 192.168.0.2:8080` fails: no TLS listener. The HSTS
header is not honored by browsers over plain HTTP, so it does not mitigate this.
Next step: terminate TLS (nginx + Let's Encrypt, or Quarkus' own TLS — see
`DEVOPS.md` §7), then keep `QUARKUS_HTTP_HOST` on loopback.

### F3 — Host validation off (pending)

Requests with a bogus `Host` header are accepted. Fine for a direct LAN bind, but
once nginx is in front set `QUARKUS_HTTP_HOST_VALIDATION_ALLOWED_HOSTS=<DNS_NAME>,localhost,127.0.0.1`
(`DEVOPS.md` §5). Do **not** disable host validation as a shortcut.

### F4 — Health endpoints (fixed)

`/q/health` answered unauthenticated and reported datastore connectivity. Now off
in the prod profile (`%prod.quarkus.smallrye-health.enabled=false`), so a packaged
jar has no `/q/health*`. If readiness checks are wanted back, bind them to a
loopback-only management interface instead (`quarkus.management.*`).

### F6 — SSH (pending, host action)

```
# on 192.168.0.2
sudo sshd -T | grep -E 'passwordauthentication|permitrootlogin|pubkeyauthentication'
# expect: passwordauthentication no | permitrootlogin no | pubkeyauthentication yes
sudo ufw allow from 192.168.0.0/24 to any port 22 proto tcp
```

## How to re-verify (from the LAN)

```bash
# 1. Document now carries CSP + anti-framing:
curl -sI http://192.168.0.2:8080/ | grep -iE 'content-security|x-frame'

# 2. No duplicate headers on REST responses (expect 1 of each):
curl -sI http://192.168.0.2:8080/api/notes | grep -ci '^x-content-type-options:'

# 3. Health is gone in prod (expect 404):
curl -s -o /dev/null -w '%{http_code}\n' http://192.168.0.2:8080/q/health/ready
```

## Remediation log

| Date | Change | Files |
|---|---|---|
| 2026-10-24 | Global CSP + `X-Frame-Options` via config; filter reduced to `X-Request-Id`; health disabled in prod; docs updated | `application.properties`, `SecurityHeadersFilter.java`, `SecurityHeadersFilterTest.java`, `README.md`, `DEVOPS.md`, `pom.xml` |
