# JobRunr reshape + measurement progress

- 22:07 start. Stage 1: reading module, harness, docs. Next: reshape OSS and Pro modules.
- 22:08 Code: OSS already one durable job with runStepOnce + bounded virtual-thread scoring; Pro already batch+continuation. Changed watcher to 15 s (OSS interval PT15S, Pro business-day cron */15). Both jars built. Old CSVs removed. Next: 1x runs OSS then Pro.
- 22:11 Stage 1 done: OSS run1 window 20.9 s (chain 4.8 s), Pro run1 window 21.3 s (chain 19.2 s), 1 LISTEN connection on Pro. Hand-offs 5.8 s and 5.9 s = poll ticks (ProcessBatchJobsTask, ProcessAwaitingJobsTask); rate limiter spreads 20 slots over the 5 s poll (4/s, javap of ProcessJobsAwaitingRateLimiterTask.getJobsToEnqueuePerSecond). Next: run2 for both, then kill tests in background.
- 22:12 Stage 2 running in background: run2 OSS/Pro, then kill tests (5 points x 2 repeats x OSS/Pro). Module README updated (15 s watcher, hand-off and 4/s rate limiter explanation). Next: results README when the batch ends.
- 22:19 Windows done (2 runs each). Kill tests: extract done both impls; scoring-partition-3 running. Bridge tests queued with deadline at 49 min.
- 22:26 Kill tests extract + scoring-partition-3 done both impls. Finding: OSS reran the completed extract after a kill in scoring; Pro did not. Running aggregate next.
- 22:42 Kill tests: 9 of 10 groups done; Pro deliver-after-call-before-record running; bridge tests start after. Writing results README.
- 22:44 All 20 kill tests done. Bridge tests running (deadline 22:56). Writing results README.
- 22:49 DONE. windows.csv (4 rows), hops.csv (4), kill-tests.csv (20), bridge.csv (4) written; results README rewritten; module README updated. No secrets in repo files (grep checked). No app processes left on 8181/8182.
