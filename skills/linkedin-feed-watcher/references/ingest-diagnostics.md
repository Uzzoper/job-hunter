# Ingest Diagnostics

## invalid-extraction (PR #113 + #115)

`invalid-extraction` is deterministic: re-sending the identical payload
(same URL + text) produces the same result. The AI classifier is
deterministic for a given input — do NOT retry expecting a different
outcome. Only retry if the payload itself was corrected (e.g. fixed
 canonical URL or expanded text that was truncated).

### Root cause (PR #113)

The classifier requires `title` to be a non-null textual string. If the
AI returns `title: null`, the guard rejects with
`ingest.ai: classification rejected — title/company not textual`.

### Company is optional since PR #115

`company` is no longer required. When the AI returns `company: null`,
the backend saves with company "Empresa não informada" (placeholder) and
logs `ingest.ai: company absent/invalid — saved with placeholder`.
Posts that name the employer get the real company name; posts that don't
still save. Only `title` being null/invalid causes rejection.

### Per-request ingest.ai logs (PR #113+)

After PR #113, the backend emits WARN lines on the rejection guards:

```
ingest.ai: empty classification reply
ingest.ai: no JSON object in the classification reply
ingest.ai: classification rejected — title/company not textual
  (titleType=... companyType=... payload={...})
ingest.ai: company absent/invalid — saved with placeholder
  (nodeType=... payload={...})
```

Capture with:
```
docker logs job-hunter-backend 2>&1 | grep "ingest.ai" | tail -10
```

The startup config WARN ("hermes.ingest-base-url is not set") is separate
and expected — ignore it.
