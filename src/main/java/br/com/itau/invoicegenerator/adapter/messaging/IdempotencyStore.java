package br.com.itau.invoicegenerator.adapter.messaging;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory deduplication for invoice integration events. Keyed by {@code (topic, eventId)} so the
 * same business event delivered to two different integrations is not collapsed.
 *
 * <p><b>Not durable.</b> A process restart loses the processed set. A real production deployment
 * must replace this with a persistent store (Redis, Postgres, etc.) so Kafka redelivery and retry
 * cannot duplicate stock deduction, invoice registration, delivery scheduling, or
 * accounts-receivable posting. See {@code docs/business-rules.md} §6 and {@code
 * .specs/features/defects-performance/spec.md} requirement DEF-PERF-12.
 */
public class IdempotencyStore {

  private final Set<Key> processed = ConcurrentHashMap.newKeySet();

  /** Returns true if the {@code (topic, eventId)} pair has already been recorded as processed. */
  public boolean alreadyProcessed(String topic, String eventId) {
    return processed.contains(new Key(topic, eventId));
  }

  /**
   * Record the event as processed. Should be called <em>after</em> the downstream side effect has
   * succeeded; if it is recorded before the call, a transient failure followed by retry would be
   * silently dropped.
   */
  public void markProcessed(String topic, String eventId) {
    processed.add(new Key(topic, eventId));
  }

  public int size() {
    return processed.size();
  }

  private record Key(String topic, String eventId) {}
}
