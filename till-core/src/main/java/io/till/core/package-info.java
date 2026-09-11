/**
 * The reservation kernel: the rules, the types they are written in, and the port they are driven
 * through.
 *
 * <p>Read in this order:
 *
 * <ol>
 *   <li>{@link io.till.core.Kernel} — the rules, as one pure function
 *   <li>{@link io.till.core.Decision} — the seam that makes "all of it or none of it" structural
 *   <li>{@link io.till.core.Ledger} — what an adapter has to promise
 *   <li>{@link io.till.core.Till} — the four-line loop a caller actually uses
 * </ol>
 *
 * <p>Nothing in this package reads a clock, opens a connection, or starts a thread. Instants arrive
 * as arguments and rows arrive in a {@link io.till.core.Snapshot}, which is what lets the simulator
 * in {@code till-testkit} run a whole day of contention in a second and reproduce any failure from
 * its seed.
 */
package io.till.core;
