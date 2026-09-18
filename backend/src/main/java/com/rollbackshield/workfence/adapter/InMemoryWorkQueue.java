package com.rollbackshield.workfence.adapter;

import com.rollbackshield.workfence.domain.WorkJob;
import com.rollbackshield.workfence.domain.WorkQueue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
@ConditionalOnProperty(name = "rollbackshield.workqueue", havingValue = "in-memory", matchIfMissing = true)
public class InMemoryWorkQueue implements WorkQueue {

    private final ConcurrentLinkedQueue<WorkJob> queue = new ConcurrentLinkedQueue<>();

    @Override
    public void enqueue(WorkJob job) {
        queue.add(job);
    }

    @Override
    public List<WorkJob> receive(int maxMessages) {
        List<WorkJob> batch = new ArrayList<>();
        for (int i = 0; i < maxMessages; i++) {
            WorkJob job = queue.poll();
            if (job == null) {
                break;
            }
            batch.add(job);
        }
        return batch;
    }
}
