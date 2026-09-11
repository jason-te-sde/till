/**
 * A client for the till HTTP API, and the {@code tillctl} command line.
 *
 * <p>No dependencies beyond {@code till-core}: the JDK's own HTTP client, and a small strict JSON
 * reader, because the most common way a client library causes trouble is by disagreeing with the
 * service that embeds it about the version of a serialisation library.
 *
 * <p>{@link io.till.client.TillClient} retries a 503 and a dropped connection with the <b>same</b>
 * idempotency key, which is the only reason retrying a request that may already have been applied is
 * safe.
 */
package io.till.client;
