package com.lld.scheduler;

/** One piece of work to run later. It may throw, and the scheduler treats a throw as "this run failed", never as a reason to stop. */
@FunctionalInterface
public interface Job {

    /** Does the work. Any exception or error thrown here is caught by the scheduler and recorded as a failure. */
    void run() throws Exception;
}
