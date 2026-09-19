/**
 * The storefront: a catalogue of games and a read model of what can still be bought.
 *
 * <p>The half of the platform that faces customers, and deliberately the half that owns no stock.
 * It reads from its own tables and writes by asking {@code till-server} over HTTP with an ordinary
 * client token, so there is exactly one place in the system where a sale is allowed, and it is not
 * here.
 *
 * <p>Availability arrives as events, through Kafka, and is projected by
 * {@link io.till.catalogue.AvailabilityProjection}. Delivery is at least once, so the projection is
 * guarded by an inbox keyed on the producer's deduplication key — the mirror of the transactional
 * outbox on the other side. Read that class first; it is where the interesting part of this service
 * is.
 */
package io.till.catalogue;
