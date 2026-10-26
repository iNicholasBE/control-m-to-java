# Quartz: misfires and DST (Spring Batch + Quartz module)

Ran on 2026-10-26 with Quartz 2.5.2 (from Spring Boot 4.0.9), Java 25.0.3 (Zulu), Apple M4 Pro. Every
statement below is asserted by a JUnit test in `nightly-close-spring-batch/src/test/java/io/example/nightlyclose/schedule/`:

```
cd nightly-close-spring-batch
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
mvn test -Dtest='QuartzMisfireTest,QuartzDstTest,BusinessCalendarTest'
```

## Misfires: the scheduler was down across the fire time

How the test simulates it: a daily cron trigger whose fire time was two minutes ago is stored before the
scheduler starts (RAMJobStore, `misfireThreshold` 1 s). That is the state the clustered JDBC store is in when
the first node comes back after an outage, and the same `CronTriggerImpl.updateAfterMisfire` code decides what
happens next.

| Policy | What happened in the test | What the job sees |
|---|---|---|
| `withMisfireHandlingInstructionFireAndProceed()` (also what the default "smart policy" does for cron) | Fired once, immediately on start, then the next fire is tomorrow at the cron time | `getScheduledFireTime()` = **the recovery moment** (17:27:55), not the missed fire time (17:25:55) |
| `withMisfireHandlingInstructionDoNothing()` | Did not fire. Next fire = tomorrow at the cron time | nothing: that night has no run unless someone starts it |
| FireAndProceed + a calendar that excludes today | **Fired anyway.** `FIRE_ONCE_NOW` sets the next fire time to "now" without consulting the calendar | a fire on a non-business day |
| FireAndProceed, but the trigger is rescheduled at boot (what `spring.quartz.overwrite-existing-jobs=true` does) | Did not fire: the replacement trigger computes its first fire from "now", so nothing ever misfired | the missed night is silently lost |

Test output (2026-10-26):

```
FIRE_AND_PROCEED: missed=2026-10-26T17:25:55Z scheduledFireTime=2026-10-26T17:27:55.313Z fireTime=2026-10-26T17:27:55.313Z next=2026-10-27T17:25:55Z
DO_NOTHING: missed=2026-10-26T17:25:49Z fires=0 next=2026-10-27T17:25:49Z
FIRE_AND_PROCEED + calendar excluding today: fires=1 scheduledFireTime=2026-10-26T17:27:52.285Z
OVERWRITE ON BOOT: missed=2026-10-26T17:25:45Z fires=0
```

What that means for the nightly close, and what the module does about it (your code, in every case):

1. **The business date cannot be "the date of the scheduled fire time".** With FireAndProceed, a Thursday
   22:30 UTC fire recovered on Friday 07:00 reports Friday 07:00 as its scheduled fire time. The trigger job
   derives the business date as "the last day whose 22:30 cron time is at or before the scheduled fire time
   and which is a business day" (`BusinessCalendar.businessDateFor`). `BusinessCalendarTest` covers: on time
   Thursday -> Thursday; recovered Friday 07:00 -> Thursday; recovered Saturday or Monday 07:00 -> Friday;
   recovered the morning after the 11 November holiday -> the Tuesday before it.
2. **FireAndProceed ignores the business-day calendar** on the catch-up fire, so a weekend recovery fires on a
   Saturday. Point 1 maps that fire back to Friday, which is the run that was missed.
3. **Neither policy catches up more than one missed night.** Down from Thursday evening to Saturday:
   FireAndProceed fires once (Friday's business date by point 1), Thursday is gone; DoNothing loses both.
   Control-M orders every missed day under its own order-date logic. In Quartz you need a catch-up check of
   your own ("for every business day since the last COMPLETED run, submit"). This module does **not**
   implement that; the identity contract (businessDate, runNumber) makes it safe to add, because submitting
   an existing date is answered with the existing run.
4. **`spring.quartz.overwrite-existing-jobs` must stay `false`** (the default; set explicitly in
   `application.yml`). With `true`, every boot reschedules the trigger and a fire missed during the outage is
   dropped before the misfire logic ever sees it. The price: a changed cron expression in `application.yml`
   does not reach a database that already holds the trigger; change it with an explicit `rescheduleJob`.
5. The stock Quartz DDL (`tables_postgres.sql`) starts with `DROP TABLE`. Running it on every boot
   (`spring.quartz.jdbc.initialize-schema=always`) wipes the stored trigger and its misfire state the same
   way. The module ships an idempotent copy (`db/schema-quartz.sql`).

Not tested here: misfire handling inside the clustered JDBC store across two JVMs (it is the same
`updateAfterMisfire` call, made by whichever node acquires the trigger first). The rolling-restart run
(`rolling-restart.csv`) shows the clustered store firing the cron trigger exactly once with two nodes up.

## DST: local-time cron vs UTC cron (Europe/Brussels)

`QuartzDstTest` computes fire times with `TriggerUtils.computeFireTimesBetween`.

**Autumn, 2026-10-25** (03:00 CEST -> 02:00 CET, the hour 02:00 to 03:00 happens twice):

| Cron | Fires around the switch (UTC) | Effect |
|---|---|---|
| `0 30 2 * * ?` in Europe/Brussels | 10-24 00:30Z, **10-25 01:30Z**, 10-26 01:30Z | Fires once on the 25th, at the **second** 02:30 (CET). The gap between the 24th and 25th fire is 25 h |
| `0 30 2 * * ?` in UTC | 02:30Z every day | 24 h apart, local clock shows 04:30 then 03:30 |

**Spring, 2027-03-28** (02:00 CET -> 03:00 CEST, 02:00 to 03:00 does not exist):

| Cron | Fires around the switch (UTC) | Effect |
|---|---|---|
| `0 30 2 * * ?` in Europe/Brussels | 03-27 01:30Z, **(none on 03-28)**, 03-29 00:30Z | **No fire at all on 28 March.** Quartz does not shift to 03:30; the night is skipped and no misfire is recorded |
| `0 30 2 * * ?` in UTC | 02:30Z every day | unaffected |

**The chain's own trigger** is `0 30 22 * * ?` in UTC (see `QuartzConfig`): six fires in six days across both
switches, never doubled or skipped. On the Brussels wall clock it moves: 00:30 CEST (the *next* local calendar
day) in summer, 23:30 CET (the same day) in winter. Two consequences:

- derive the business date from the UTC date of the fire, never from the local date (in summer the local date
  is already D+1);
- if operations think in local time ("the close starts at half past midnight"), that statement is true for
  seven months a year. A local-time cron at 22:30 Europe/Brussels avoids that, and 22:30 is outside the
  02:00 to 03:00 window, so it would be DST-safe too; only crons inside that window double up or vanish.

The 06:00 SLA in this module is evaluated in UTC by default (`chain.sla.zone`). An SLA written as "06:00
Brussels time" is `chain.sla.zone=Europe/Brussels`; `Sla.deadline` uses `ZonedDateTime`, so it is correct on
both switch days.
