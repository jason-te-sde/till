package io.till.server;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Skips a suite that needs PostgreSQL when there is none, and fails it when there is none in CI.
 *
 * <p>Skipping locally is a kindness: somebody reading the code can run {@code mvn verify} and watch
 * the kernel's suite pass without installing anything. Skipping in CI is not, because a green tick
 * that means "the adapter was never tested on this change" is worse than a red one.
 *
 * @see TestDatabase
 */
final class RequiresDatabase implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        if (TestDatabase.isAvailable()) {
            return ConditionEvaluationResult.enabled("a database is reachable");
        }
        if (System.getenv("CI") != null) {
            throw new IllegalStateException(
                    "CI is set and there is neither Docker nor " + TestDatabase.URL_ENV
                            + ": the PostgreSQL suite would be skipped, and a skipped suite must not look "
                            + "like a passing one");
        }
        return ConditionEvaluationResult.disabled(
                "no Docker and no " + TestDatabase.URL_ENV + "; set one to run the PostgreSQL suite");
    }
}
