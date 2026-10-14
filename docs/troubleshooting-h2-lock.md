# macOS: H2 "Database may be already in use" (90020)

## Symptom

Spring Boot fails during `JdbcTemplate` / `dataSourceScriptDatabaseInitializer`
setup. The **root cause** at the bottom of the stack trace is:

```text
org.h2.jdbc.JdbcSQLNonTransientConnectionException:
Database may be already in use: .../data/trading-mentor.mv.db [90020]
Caused by: org.h2.mvstore.MVStoreException: The file is locked
```

This means **another process** holds an OS-level lock on the same embedded
file-backed H2 database. The surrounding `UnsatisfiedDependencyException`
messages are *secondary symptoms*, not missing Spring beans. A different
Java, Gradle, or IntelliJ run configuration may still be running an older
instance, even if the terminal you are using looks idle.

## Safe recovery without losing alert or paper-trading data

1. In `~/Desktop/trading-mentor-api`, inspect the OS lock and port:

```bash
cd ~/Desktop/trading-mentor-api
lsof -nP "$PWD/data/trading-mentor.mv.db"
lsof -nP -iTCP:8080 -sTCP:LISTEN
```

2. If the PID belongs to a previous `TradingMentorApplication`, first use
   **Ctrl+C in the original terminal** (or IntelliJ Stop). If it cannot be
   stopped there, inspect it with `ps -p PID -o pid,ppid,command`, then use
   `kill -TERM PID` *only after confirming it is the old local instance*.
   Avoid broad `pkill java` or `kill -9`.
3. Verify the lock is released. If an unrelated process owns it, identify
   and stop that process; do not force open the file.
4. Start through the non-destructive preflight script:

```bash
bash scripts/start-local.sh
```

It loads your existing local `.env`, inspects H2 and port 8080 with
`lsof`, refuses to launch a second process, and invokes `./gradlew bootRun`.
It intentionally **never kills a process or deletes the DB**.

5. When running, check health and outbox receipts from another shell:

```bash
curl -fsS http://127.0.0.1:8080/api/v1/health
curl -fsS http://127.0.0.1:8080/api/v1/alerts/status
```

## Data safety / why this happens

- The configured JDBC URL is `jdbc:h2:file:./data/trading-mentor;DB_CLOSE_ON_EXIT=FALSE`.
  File-backed **embedded H2 permits one opening process** by default.
- `gradlew bootRun` creates a Java server process. Running it again in
  another terminal, or from IntelliJ simultaneously, may collide with the
  same database. Do not use two Java schedulers against the same H2 DB.
- Do **not** delete `data/trading-mentor.mv.db`, H2 files, alert tables,
  or your `.env`. This risks losing durable alert state and paper journals.
- Do not add `AUTO_SERVER=TRUE` to work around this error. That can allow
  competing schedulers and duplicate Telegram delivery; this local MVP is
  intentionally single-instance.
- For independent *disposable tests*, use an isolated **in-memory** H2
  datasource by configuring `SPRING_DATASOURCE_URL` only on the test run,
  not on the persistent bot. Such a new database will have **none** of your
  previous alerts or trades.
- Multi-instance server deployment should move to PostgreSQL with versioned
  migrations, lease coordination, and authentication before it is attempted.

## If lsof shows no lock owner

A PID may be on another user/session or the underlying filesystem may not
support the needed lock. Verify file ownership and permission, inspect
`lsof` with the appropriate privileges, and inspect the full error chain.
Do not assume deleting the file is a safe fix.
