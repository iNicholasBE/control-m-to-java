-- timegap.sql: hidden-dependency candidates in one Postgres query.
--
-- Same rule as ctminv/timegap.py: (A, B) is a candidate when B starts within
-- 15 minutes after A ends on at least 90% of the nights both ran (and at least
-- 10 such nights), and the definitions have no path from A to B.
--
-- Inputs (load with \copy ... csv header):
--   run_history(job_name text, folder text, order_date date,
--               start_time timestamp, end_time timestamp, status text, host text)
--       the CSV that `python -m ctminv history ...` writes, or your own export
--   definition_edges(src_folder text, src_job text, dst_folder text, dst_job text,
--                    kind text, label text)
--       the CSV that `python -m ctminv edges <definition files> --out edges.csv` writes
--
-- Recommended index:
--   create index on run_history (order_date, start_time);
--   create index on definition_edges (src_folder, src_job);
--
-- Checked: parsed with the PostgreSQL parser (pglast 8.7 / libpg_query) and run on DuckDB 2.0
-- against ctminv/timegap.py on a 1,500-job, 60-night synthetic estate: the same 4,413 pairs with
-- identical counts, median gaps and tracking bounds. Not yet run on a PostgreSQL server.
--
-- Cost: the hits join is one index range scan per job-night, but on 9,000 jobs x 90 nights it
-- produces tens of millions of intermediate rows. On the full synthetic estate DuckDB had not
-- finished after 13 minutes (the Python version takes about 40 s), so start with a subset of
-- folders or a shorter history and give PostgreSQL generous work_mem.

WITH RECURSIVE
params AS (
    SELECT interval '15 minutes' AS win, 0.90::numeric AS min_conf, 10 AS min_nights
),
nightly AS (            -- one row per job and night: first start, last end (a rerun counts as one long run)
    SELECT folder, job_name, order_date,
           min(start_time) AS start_time, max(end_time) AS end_time
    FROM run_history
    GROUP BY folder, job_name, order_date
),
hits AS (               -- nights where B started 0..15 min after A ended
    SELECT a.folder AS a_folder, a.job_name AS a_job,
           b.folder AS b_folder, b.job_name AS b_job,
           count(*) AS nights_hit,
           -- does B's start move with A's end? (seconds since midnight of the order date)
           corr(extract(epoch FROM b.start_time - b.order_date::timestamp),
                extract(epoch FROM a.end_time   - a.order_date::timestamp)) AS tracking,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM b.start_time - a.end_time)) AS median_gap_s
    FROM nightly a
    JOIN nightly b
      ON b.order_date = a.order_date
     AND b.start_time >= a.end_time
     AND b.start_time <= a.end_time + (SELECT win FROM params)
     AND (b.folder, b.job_name) IS DISTINCT FROM (a.folder, a.job_name)
    GROUP BY a.folder, a.job_name, b.folder, b.job_name
    HAVING count(*) >= (SELECT ceil(min_nights * min_conf) FROM params)
),
together AS (           -- nights both ran, only for pairs that can still qualify
    SELECT h.a_folder, h.a_job, h.b_folder, h.b_job, count(*) AS nights_both_ran
    FROM hits h
    JOIN nightly x ON x.folder = h.a_folder AND x.job_name = h.a_job
    JOIN nightly y ON y.folder = h.b_folder AND y.job_name = h.b_job AND y.order_date = x.order_date
    GROUP BY h.a_folder, h.a_job, h.b_folder, h.b_job
),
candidates AS (
    SELECT h.*, t.nights_both_ran,
           round(h.nights_hit::numeric / t.nights_both_ran, 4) AS confidence
    FROM hits h
    JOIN together t USING (a_folder, a_job, b_folder, b_job)
    CROSS JOIN params p
    WHERE t.nights_both_ran >= p.min_nights
      AND h.nights_hit >= p.min_conf * t.nights_both_ran
),
reach AS (              -- everything downstream of each candidate's A in the definitions
    SELECT DISTINCT c.a_folder AS root_folder, c.a_job AS root_job, e.dst_folder, e.dst_job
    FROM candidates c
    JOIN definition_edges e ON e.src_folder = c.a_folder AND e.src_job = c.a_job
    UNION
    SELECT r.root_folder, r.root_job, e.dst_folder, e.dst_job
    FROM reach r
    JOIN definition_edges e ON e.src_folder = r.dst_folder AND e.src_job = r.dst_job
)
SELECT c.a_folder, c.a_job, c.b_folder, c.b_job,
       c.nights_both_ran, c.nights_hit, c.confidence,
       round(c.median_gap_s::numeric, 1) AS median_gap_s,
       round(c.tracking::numeric, 3) AS tracking,
       -- 99.9% lower bound of the correlation (Fisher z), as --min-tracking uses
       round(tanh(atanh(greatest(least(c.tracking, 0.999999), -0.999999))
                  - 3.29 / sqrt(c.nights_hit - 3))::numeric, 3) AS tracking_lb
FROM candidates c
WHERE NOT EXISTS (
        SELECT 1 FROM reach r
        WHERE r.root_folder = c.a_folder AND r.root_job = c.a_job
          AND r.dst_folder = c.b_folder AND r.dst_job = c.b_job)
  -- optional, like --min-tracking 0.3: keep pairs where B's start follows A's end
  -- AND tanh(atanh(greatest(least(c.tracking, 0.999999), -0.999999)) - 3.29 / sqrt(c.nights_hit - 3)) >= 0.3
ORDER BY c.confidence DESC, c.nights_both_ran DESC, c.a_folder, c.a_job, c.b_folder, c.b_job;
