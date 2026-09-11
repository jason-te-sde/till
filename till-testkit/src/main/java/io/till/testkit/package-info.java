/**
 * A deterministic concurrency simulator and the properties it checks.
 *
 * <p>Read in this order:
 *
 * <ol>
 *   <li>{@link io.till.testkit.Sim} — why a command is three phases rather than one
 *   <li>{@link io.till.testkit.Invariants} — the properties, and what each one catches
 *   <li>{@link io.till.testkit.History} — what the invariants structurally cannot see
 *   <li>{@link io.till.testkit.Flaw} — the mistakes the suite is proven to notice
 * </ol>
 *
 * <p>Usable against a {@link io.till.core.Ledger} of your own: implement the port, hand it to
 * {@link io.till.testkit.Sim#Sim(io.till.testkit.SimConfig, io.till.core.Ledger)}, and the same
 * schedules that check the two adapters here will check yours.
 */
package io.till.testkit;
