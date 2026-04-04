package com.ashutosh.jiranotionsync.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Manages the Redis queue for async sync event processing.
 * When a Jira webhook comes in, we save it to DB and push the event ID
 * to this queue. A separate worker picks it up and processes it.
 * This decouples ingestion from processing — webhook returns fast,
 * heavy Notion API calls happen in background.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SyncQueueService {

    private static final String SYNC_QUEUE_KEY = "jira:notion:sync:queue";
    private static final String DEAD_LETTER_KEY = "jira:notion:sync:dead-letter";

    private final StringRedisTemplate redisTemplate;

    /**
     * Pushes an event ID to the Redis queue for processing.
     *
     * @param eventId UUID string of the SyncEvent to process
     */
    public void enqueue(String eventId) {
        redisTemplate.opsForList().rightPush(SYNC_QUEUE_KEY, eventId);
        log.debug("Enqueued event: {} to Redis queue", eventId);
    }

    /**
     * Pops the next event ID from the queue (blocking, 0 = wait forever).
     *
     * @return event ID string or null if queue is empty
     */
    public String dequeue() {
        return redisTemplate.opsForList().leftPop(SYNC_QUEUE_KEY);
    }

    /**
     * Moves a failed event to dead letter queue after max retries exceeded.
     *
     * @param eventId UUID string of the failed SyncEvent
     */
    public void moveToDeadLetter(String eventId) {
        redisTemplate.opsForList().rightPush(DEAD_LETTER_KEY, eventId);
        log.warn("Moved event: {} to dead letter queue", eventId);
    }

    /**
     * Returns current queue size — useful for monitoring.
     */
    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(SYNC_QUEUE_KEY);
        return size != null ? size : 0;
    }

    /**
     * Returns dead letter queue size — useful for alerting.
     */
    public long getDeadLetterSize() {
        Long size = redisTemplate.opsForList().size(DEAD_LETTER_KEY);
        return size != null ? size : 0;
    }
}
