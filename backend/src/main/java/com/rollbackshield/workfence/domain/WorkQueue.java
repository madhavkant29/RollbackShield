package com.rollbackshield.workfence.domain;

import java.util.List;

/**
 * Port for the async work transport. InMemoryWorkQueue backs local dev;
 * SqsWorkQueueAdapter backs the 'aws' profile. Application/domain code
 * depends only on this interface.
 */
public interface WorkQueue {
    void enqueue(WorkJob job);
    List<WorkJob> receive(int maxMessages);
}
