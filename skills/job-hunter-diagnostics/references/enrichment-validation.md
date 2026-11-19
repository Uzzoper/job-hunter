# Validating an enrichment run without touching the live database

Use this when asked to validate a batch endpoint (company-site enrichment,
backfill, resume tailoring) "with local tests and isolated data". The default
answer is **unit tests with fakes plus a log-capture harness** — do not point
the real endpoint at the development database to "see what happens".

## Isolation order

1. **Prefer the existing suite.** Run the service and adapter tests first
   (`./mvnw test -Dtest='<Service>Test,<Adapter>Test,<Controller>Test'`) — they
   already pin the contract and need no database.
2. **Add a throwaway harness for what the suite does not assert** (log output,
   counting semantics). Write it under `src/test/`, run it, then **delete the
   file and its compiled `target/test-classes/*.class`** before reporting. A
   scratch class left in the tree is an uncommitted change that will look like
   product later.
3. **Never run the endpoint against the development database** when the user
   asked for isolation. Record the counts before/after to prove it was untouched.

## Capturing log output — `ListAppender`, not a stream

Redirecting `System.err` captures nothing: the logging framework owns its
appenders. Attach a Logback appender to the component's logger and read the
events back:

```java
Logger target = (Logger) LoggerFactory.getLogger(Component.class);
ListAppender<ILoggingEvent> appender = new ListAppender<>();
appender.start();
target.addAppender(appender);
// ... exercise ...
appender.list.stream().map(e -> e.getLoggerName() + " " + e.getLevel()
        + " " + e.getFormattedMessage()).collect(Collectors.joining(" || "));
target.detachAppender(appender);
```

**Assert that the appender actually captured events** (include the logger name
in the assertion). An empty capture reads identically to "the component is
silent" — the two must be told apart before concluding anything about
observability.

## The gap to test: silent outcomes

A counter that only increments on failure hides every *successful but empty*
outcome. Assert per outcome class, not just the happy path: found / no-result /
cache-hit / policy-blocked / failure. For each, state which log line carries it
and at which level — `DEBUG` is invisible at the container's default level, so
"the code logs it" is not the same as "the operator sees it".

Report the level honestly: a class whose only trace is `DEBUG` is **not**
distinguishable in production.

## WireMock isolation traps

- **Stubs match by PATH, not host.** Two jobs whose `companyWebsite` differ only
  by path share one host, so one stub set serves both and per-scenario variants
  silently collide. Give each scenario its **own WireMock port** so the host —
  and therefore any per-host cache — is genuinely distinct.
- A cache keyed by host makes repeat calls on the same host a cache hit, not a
  second crawl. If a scenario needs a fresh crawl, change the host.
- `OwnerEmailGuard` with a `null`/empty repository is fine; a repository that
  returns an empty owner set makes the guard keep candidates.

## Counting semantics: pin what the counter counts

Before trusting a result object, pin with a scratch test what each counter means
and which outcomes increment it. In particular:

- `checked` counts **candidates** (rows), not distinct domains.
- A shared domain is crawled once but its result applies to every job of that
  domain, so `enriched` counts jobs, not crawls.
- A crawl that succeeds but yields nothing must not count as `failed`; a policy
  block or a crawl failure must. Whether that holds depends on whether the
  component **returns** a reason or swallows it: a never-throw component that
  returns the input unchanged collapses "blocked" and "no email" into the same
  no-op, and the batch counts neither as failure. Test the reason contract
  directly rather than inferring it from the happy path.

Assert the failure case with a component that **throws**, not one that returns
unchanged — the two exercise different branches.

## Reporting

Per item requested: the test executed, its result, and the observed
log/counter output. Then the verdict on the original question, naming exactly
which classes are distinguishable at the default level and which are not.
State explicitly that no commit/push happened and that the scratch files were
removed.
