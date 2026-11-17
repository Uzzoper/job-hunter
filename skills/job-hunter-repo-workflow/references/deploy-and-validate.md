# Deploy a backend slice + validate its endpoint contract

Recipe for landing a change and proving the new HTTP surface actually works,
instead of trusting the PR body. The resume endpoint is the worked example;
swap in whichever route the slice adds.

## 1. Land the code

```bash
cd <job-hunter-repo>
git fetch origin
git checkout main
git pull --ff-only origin main      # bare `git pull` aborts here (no upstream)
git log --oneline -3                # confirm the expected merge / tip
```

## 2. Rebuild + restart (host-network compose)

```bash
docker compose build backend
docker compose up -d --force-recreate backend   # run with background=true;
                                                # the tool flags a bare `up -d`
```

Poll readiness instead of sleeping blind, then prove the app answers:

```bash
for i in $(seq 1 30); do
  s=$(docker inspect -f '{{.State.Health.Status}}' job-hunter-backend)
  echo "$i: $s"; [ "$s" = healthy ] && break; sleep 5
done
docker ps --format '{{.Names}}\t{{.Status}}' | grep backend
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "X-Bot-Token: $TOKEN" http://localhost:8080/api/jobs
```

`/actuator/health` requires auth (401) — use `GET /api/jobs` as the readiness
probe. `GET /api/jobs` must answer 200 with the bot header.

## 3. Validate the endpoint contract

Assert every field, not just the status:

```bash
curl -sS -o /tmp/art.pdf \
  -w 'http=%{http_code} type=%{content_type} bytes=%{size_download}\n' \
  -H "X-Bot-Token: $TOKEN" http://localhost:8080/api/profile/resume
python3 -c "print(open('/tmp/art.pdf','rb').read(5))"   # -> b'%PDF-'
```

- status, `content_type`, magic bytes and size — all four, before calling it
  working.
- **Artifact identity**: hash the served bytes against the copy the bot
  actually consumes (`md5sum`). A serving path that resolves to a *different*
  stored artifact is the defect, not a broken endpoint — report the mismatch,
  never overwrite either side to make it pass.

## 4. Negative check

No token and a wrong token must both be rejected (401/403). An endpoint that
answers without the bot header is a leak.

## 5. Report

Per check: the command, the observed value, the expected value, PASS/FAIL.
Never report "the endpoint works" from the status code alone.
