# Rules for this repository

1. Never commit JobRunr Pro credentials, licence keys or Maven settings. The Pro modules read `JOBRUNR_REPO_USER` and `JOBRUNR_REPO_PASSWORD` from the environment. If you do not have them, request a free trial at https://www.jobrunr.io/en/try-jobrunr-pro/ and the open-source modules still run.
2. Every measured number in `results/` comes with the command that produced it, the hardware it ran on and the date.
3. One module per directory. Root files (README, LICENSE, this file, .gitignore) are edited by the maintainers only.
