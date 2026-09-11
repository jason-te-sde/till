/**
 * The PostgreSQL adapter: {@link io.till.jdbc.JdbcLedger} and the schema it needs.
 *
 * <p>Nothing here knows the reservation rules. It reads the rows a command needs at one instant,
 * writes a decision in one transaction or not at all, and answers "somebody got there first" with a
 * {@code false} rather than an exception.
 */
package io.till.jdbc;
