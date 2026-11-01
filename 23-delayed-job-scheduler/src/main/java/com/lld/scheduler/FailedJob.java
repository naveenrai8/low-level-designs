package com.lld.scheduler;

/**
 * One entry of the dead-letter list: a one-shot job that used up its attempts, or one failed run of a repeating job (the repeating job itself keeps running).
 *
 * @param jobId the id of the job that failed
 * @param attempts how many times the work had started when it failed for the last time
 * @param error what the last attempt threw
 */
public record FailedJob(long jobId, int attempts, Throwable error) {
}
