package org.jobrunr.examples.nightlyclose.contract;

/**
 * The only thing OSS and Pro implement differently: turning a submitted run into scheduled jobs.
 * Implementations must be idempotent, because the contract calls them again for a run that is still SUBMITTED.
 */
public interface ChainLauncher {

    void launch(ChainRun run);
}
