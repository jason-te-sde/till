package io.till.testkit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proof that the suite would notice.
 *
 * <p>A green test run says something about the code only if the tests can go red. Each case here
 * puts back a mistake a hand-written implementation of this problem plausibly makes, and asserts that
 * the simulator fails — and which check failed, so that a change which quietly turns one of these
 * into a different symptom is visible in the diff rather than only in the count.
 *
 * <p>Every one of them is caught within the first few thousand steps of a single seed.
 */
class FlawDetectionTest {

    static Stream<Arguments> flaws() {
        return Stream.of(
                Arguments.of(
                        Flaw.LOST_UPDATE,
                        "The ledger agrees with its own audit log",
                        "two callers decide against the same version and the later write wins"),
                Arguments.of(
                        Flaw.NO_IDEMPOTENCY,
                        "Deduplication keys are unique",
                        "a retried command runs a second time and emits the same event twice"),
                Arguments.of(
                        Flaw.PARTIAL_APPLY,
                        "The ledger agrees with its own audit log",
                        "the state moves and the outbox row that describes it is dropped"),
                Arguments.of(
                        Flaw.RESERVED_IGNORED,
                        "The kernel can decide any command against any snapshot",
                        "availability read as on-hand, so held stock is handed out twice"));
    }

    @ParameterizedTest(name = "{0} is caught by: {1}")
    @MethodSource("flaws")
    @DisplayName("a known mistake fails the run, and the named check is what fails")
    void isCaught(Flaw flaw, String expectedCheck, String description) {
        SimConfig config = SimConfig.defaults(8123).withFlaw(flaw);

        InvariantViolation violation = assertThrows(InvariantViolation.class, () -> Sim.run(config));

        assertTrue(
                violation.getMessage().startsWith(expectedCheck),
                flaw + " (" + description + ") was expected to fail '" + expectedCheck + "' but failed: "
                        + violation.getMessage());
        assertTrue(violation.step() > 0, "the violation should name the step that produced it");
    }

    @ParameterizedTest(name = "{0} is caught on most seeds, not one lucky one")
    @MethodSource("flaws")
    @DisplayName("detection does not depend on the seed")
    void isCaughtOnEverySeed(Flaw flaw, String expectedCheck, String description) {
        for (long seed = 1; seed <= 20; seed++) {
            SimConfig config = SimConfig.defaults(seed).withFlaw(flaw);
            assertThrows(
                    InvariantViolation.class,
                    () -> Sim.run(config),
                    flaw + " (" + description + ") survived seed " + config.seed() + " undetected");
        }
    }
}
