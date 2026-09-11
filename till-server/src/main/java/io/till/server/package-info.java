/**
 * The HTTP service.
 *
 * <p>A thin layer, on purpose: parse a request, run one {@link io.till.core.Command}, turn the
 * {@link io.till.core.Outcome} into a status code. Anything here that started making a decision of
 * its own would be a rule the simulator cannot reach.
 */
package io.till.server;
