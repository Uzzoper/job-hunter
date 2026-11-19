# Isolated verification of a branch fix (tests + logs, no side effects)

Recipe for proving a claimed behaviour without touching the runtime: no
endpoint call, no real HTTP, no database, no commit. `deploy-and-validate.md`
is the counterpart that DOES exercise the running service.

## 1. Confirm the commit before anything else

```bash
cd <job-hunter-repo>
git fetch origin --quiet
git ls-remote origin refs/heads/<branch>     # the SHA the owner published
git checkout --detach <sha>                    # exact SHA, not the branch tip
git rev-parse HEAD                             # must equal the ls-remote value
```

State both SHAs in the report. A branch moves between the owner's message and
your run; a green suite on the previous commit validates nothing.

## 2. Run the named test classes

```bash
./mvnw test --batch-mode -Dtest='ClassA,ClassB' 2>&1 | tee /tmp/suite.log
```

Read the per-class `Tests run:` lines, not just the final BUILD line — a
narrowed `-Dtest` that silently matches zero tests still prints BUILD SUCCESS.
`grep -E 'Tests run:.*in |BUILD' /tmp/suite.log` gives the breakdown.

## 3. Assert log output at the default level

`System.setErr(...)` / a `ByteArrayOutputStream` does **not** capture logback
output under Maven surefire — the appender owns the stream, so the captured
buffer stays empty and the test passes vacuously on a missing line. Attach a
`ListAppender` to the named logger instead:

```java
Logger logger = (Logger) LoggerFactory.getLogger(TheClassUnderTest.class);
ListAppender<ILoggingEvent> appender = new ListAppender<>();
appender.start();
logger.addAppender(appender);
// ... drive the code ...
List<String> info = appender.list.stream()
        .filter(e -> e.getLevel().equals(Level.INFO))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
logger.detachAppender(appender);          // @AfterEach
```

Filter by level: the question is usually "is this line visible at INFO in
production", so assert on INFO specifically rather than on "any event". Print
the captured lines into the report — they are the evidence.

## 4. Isolate HTTP: WireMock matches by PATH, not host

`wm.stubFor(get(urlEqualTo("/robots.txt")))` matches `/robots.txt` on **every**
host that WireMock port serves. Two scenarios differing only by host therefore
share stubs and produce false results (a "no email" scenario silently reading
the "email found" stub). Give each distinct host its own WireMock port —
`new WireMockServer(wireMockConfiguration().dynamicPort())` per scenario — or
match on `urlPathEqualTo` plus an explicit host assertion. A shared port also
collapses per-host cache scenarios, since the cache key is the host.

## 5. Counting and caching: drive two consecutive calls

A failure branch that memoises its result makes the second call take a
different path. Assert both calls — log line per call, throw/no-throw per call,
and the counter. `verify(2, getRequestedFor(urlPathEqualTo("/robots.txt")))`
proves the second call really hit the server (nothing cached);
`wm.countRequestsMatching(...)` before/after proves the opposite. Reading the
source is not enough here: the cache write is often one line inside the failure
branch.

## 6. Clean up

```bash
rm -f src/test/java/**/Scratch*.java
rm -f target/test-classes/**/*Scratch*.class     # a stale class keeps running
find src/test -iname '*Scratch*'                  # expect empty
git status --short                              # expect no new entries
```

## 7. Report shape

HEAD (local vs remote SHA) → commands executed with their results → one block
per requirement with the captured evidence inline → residual findings →
cleanup confirmation. Unverified requirements are stated as unverified, never
omitted.
