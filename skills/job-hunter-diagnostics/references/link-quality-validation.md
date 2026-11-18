# Validating a link-quality / data-quality fix

The project has accumulated a series of data-quality defects in the resolved
`company_website` column: portal URLs persisted instead of real sites, tech-token
hosts (`Node.js`, `React.js`, `gera.Java`, `ASP.NET`, `Nest.js`), shortener hosts
(`bit.ly`), file-extension TLDs treated as extensions instead of real TLDs,
tracking params (`gclid`, `fbclid`, `msclkid`, `gad=`), and internal/tenant URLs
(SharePoint, OneTrust, `/_layouts/`, `utm_`).

Fixing one of these is not finished when the code merges. The requirement is
**proof the run produced clean data** — a fix that stops creating new pollution
while the existing 1200+ rows keep serving it is half a fix, and a fix that
silently starts accepting new pollution is worse than none.

## Procedure

### 1. Snapshot before the run

```python
import json, sqlite3
c = sqlite3.connect('<job-hunter-repo>/data/jobhunter.db')
rows = c.execute(
    "SELECT id, company_website FROM jobs "
    "WHERE company_website IS NOT NULL AND company_website!=''").fetchall()
json.dump({str(r[0]): r[1] for r in rows}, open('/tmp/pre_websites.json', 'w'))
```

Dump keyed by `str(id)` — see the key-type trap below. Record the count; it is
the denominator in the final report.

### 2. Dry-run first, then a bounded number of apply rounds

```bash
TOKEN=$(grep BOT_SERVICE_API_KEY <job-hunter-repo>/.env | cut -d= -f2)
curl -s -X POST "http://127.0.0.1:8080/api/jobs/backfill-websites?dryRun=true&maxHosts=50" \
  -H "X-Bot-Token: $TOKEN"
# {"scanned":2151,"filled":3,"stillNull":2148}

curl -s -X POST "http://127.0.0.1:8080/api/jobs/backfill-websites?dryRun=false&maxHosts=50" \
  -H "X-Bot-Token: $TOKEN"
```

Honor whatever bound the user set ("at most 2 rounds", "stop even if it hasn't
converged") and say whether it converged.

**A dry-run does not persist, so `filled` in the first apply round is not
bounded by the dry-run's `filled`.** A later round legitimately fills more than
an earlier one even when `scanned` went down, because the queue advances against
real state. Do not read that as a measurement error.

### 3. Diff, then scan only what the run added

```python
pre = {str(k): v for k, v in json.load(open('/tmp/pre_websites.json')).items()}
post = {str(k): v for k, v in c.execute(
    "SELECT id, company_website FROM jobs "
    "WHERE company_website IS NOT NULL AND company_website!=''").fetchall()}

added   = {k: v for k, v in post.items() if k not in pre}
changed = {k: (pre[k], v) for k, v in post.items() if k in pre and pre[k] != v}
removed = [k for k in pre if k not in post]
```

Report **distinct** values, not rows. Twelve rows can be two hosts.

```python
distinct = sorted(set(added.values()))
```

### 4. Scan for every class of pollution

```python
TECH  = re.compile(r'node\.js|react\.js|gera\.java|asp\.net|\.net|angular\.js|vue\.js|spring\.io|laravel\.com|dotnet', re.I)
SHORT = re.compile(r'bit\.ly|tinyurl|t\.co|goo\.gl|is\.gd|cutt\.ly|ow\.ly|buff\.ly', re.I)
EXTTLD = re.compile(r'\.(cpp|js|py|java|net|php|rb|go|rs|cs|ai|io|ts|jsx|css|html|json|xml|sh|exe|msi|deb|rpm|apk)$', re.I)
TRACK = re.compile(r'gclid|fbclid|msclkid|gad=|utm_|_nfpb|nfls|_pageLabel|_layouts|sharepoint\.com|onetrust', re.I)

for d in distinct:
    host = d.split('/')[2] if '://' in d else d
    for name, rx in [('tech-token', TECH), ('shortener', SHORT),
                     ('ext-tld', EXTTLD), ('tracking/leak', TRACK)]:
        if rx.search(host):
            print('!!', name, d)
```

Scan the **host**, not the full URL — a tracking param in the query string is
invisible if you match against the whole value and the regex anchors on the end.

**Report zero as a list, not as a bare "0".** "Zero: no tech-tokens, no
shorteners, no extension TLDs, no tracking/internal URLs" is checkable;
`POLUIÇÃO DETECTADA: 0` leaves the reader guessing which categories were
actually tested.

## Key-type trap — the one that produces a spectacularly wrong number

A snapshot dumped with `json.dump` turns integer dict keys into strings. Loading
it and diffing against a fresh `sqlite3` query (integer keys) then makes **every
row look new and every old row look removed**:

```
NOVOS: 1269 | ALTERADOS: 0 | REMOVIDOS: 1257   # wrong, and alarming
```

The real answer was 12 added, 0 changed, 0 removed. Normalize both sides on
load (`{str(k): v for k, v in json.load(...).items()}`) and re-check that
`added + unchanged == post` before believing any number.

**A diff that reports more changes than the run could plausibly have made is a
key-type bug until proven otherwise.** Cross-check against the round's reported
`filled` before reporting.

## Enrichment is the time sink, not resolution

`POST /api/jobs/enrich-emails?limit=200` crawls real company sites with 3
retries each, so a large cap can outlive the client waiting on it. When the
client times out, the container keeps working — poll the row count until it
stops moving rather than retrying (a retry doubles the load on an already
throttled host):

```python
PREV = ""
for i in range(30):
    N = c.execute("SELECT COUNT(*) FROM jobs WHERE contact_email IS NOT NULL AND contact_email!='').fetchone()[0]
    if N == PREV and i > 2:
        break   # stable
    PREV = N
    sleep(20)
```

Distinguish the two expected failure shapes in the WARN log: DNS/unknown host
(`I/O error ... <host>`) and exhausted retries (`All 3 retry attempts exhausted`)
are dead domains and will never resolve; `403` (Akamai/CDN) and `404` on a
`/contato` path are reachable hosts with a wrong path. The second class is worth
fixing, the first is not worth retrying.

A large `stillNull` against a small `filled` is the signal that the backlog is
mostly unrecoverable — say so, because it means the next run buys little for
minutes of wall clock.

## Reporting shape

State the diff in the order the user asked for it, and end with an explicit
verdict line. When the run is clean, that line is the whole deliverable; when it
is not, name the class and the value so it can be acted on immediately.
