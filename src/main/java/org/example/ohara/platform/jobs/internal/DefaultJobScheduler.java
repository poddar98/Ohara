package org.example.ohara.platform.jobs.internal;

import org.example.ohara.platform.jobs.JobRequest;
import org.example.ohara.platform.jobs.JobScheduler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class DefaultJobScheduler implements JobScheduler {

    private final JobStore store;
    private final JsonMapper jsonMapper;

    DefaultJobScheduler(JobStore store, JsonMapper jsonMapper) {
        this.store = store;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean enqueue(JobRequest request) {
        return store.insert(request, jsonMapper.writeValueAsString(request.payload()));
    }

    @Override
    public int discard(String jobType, String payloadField, String value) {
        return store.discard(jobType, payloadField, value);
    }
}
