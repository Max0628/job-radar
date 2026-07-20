package dev.jobradar.common.kafka;

public final class Topics {

    public static final String JOBS_DISCOVERED = "jobs.discovered";
    public static final String JOBS_RAW = "jobs.raw";
    public static final String JOBS_EVENTS = "jobs.events";

    public static final String JOBS_DISCOVERED_DLQ = JOBS_DISCOVERED + ".dlq";
    public static final String JOBS_RAW_DLQ = JOBS_RAW + ".dlq";
    public static final String JOBS_EVENTS_DLQ = JOBS_EVENTS + ".dlq";

    private Topics() {
    }
}
