package org.example.ohara.platform.jobs;

/** The job can never succeed as submitted (bad payload, rejected input); it is marked FAILED without retries. */
public class NonRetryableJobException extends RuntimeException {

    public NonRetryableJobException(String message) {
        super(message);
    }

    public NonRetryableJobException(String message, Throwable cause) {
        super(message, cause);
    }
}
