package io.till.client;

import io.till.core.IdempotencyKey;
import io.till.core.Line;
import io.till.core.Outcome;
import io.till.core.ReservationId;
import io.till.core.Sku;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The command line.
 *
 * <pre>{@code
 * export TILL_URL=http://localhost:8080 TILL_TOKEN=...
 *
 * tillctl adjust widget 100
 * tillctl stock widget
 * tillctl reserve widget:2 gadget:1 --ttl=900
 * tillctl commit <reservation-id>
 * tillctl get <reservation-id>
 * }</pre>
 *
 * <p>Idempotency keys are generated per invocation unless {@code --key} is given. That is the right
 * default for a person at a terminal and the wrong one for a script: a script that retries a
 * {@code reserve} without a fixed key takes a second hold, so {@code --key} is how a script says what
 * it means.
 */
public final class Tillctl {

    private Tillctl() {}

    /**
     * @param args the command and its arguments
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs one command.
     *
     * @param args the command and its arguments
     * @param out where results go
     * @param err where failures go
     * @return the process exit code: 0 for success, 1 for a refusal, 2 for a usage error
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        List<String> positional = new ArrayList<>();
        Map<String, String> flags = new LinkedHashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq < 0) {
                    flags.put(arg.substring(2), "true");
                } else {
                    flags.put(arg.substring(2, eq), arg.substring(eq + 1));
                }
            } else {
                positional.add(arg);
            }
        }
        if (positional.isEmpty() || flags.containsKey("help")) {
            usage(out);
            return positional.isEmpty() ? 2 : 0;
        }

        String url = flags.getOrDefault("url", envOr("TILL_URL", "http://127.0.0.1:8080"));
        String token = flags.getOrDefault("token", System.getenv("TILL_TOKEN"));
        TillClient client = TillClient.builder(url).token(token).build();
        IdempotencyKey key = IdempotencyKey.of(flags.getOrDefault("key", "tillctl-" + UUID.randomUUID()));

        try {
            return dispatch(positional, flags, client, key, out, err);
        } catch (TillApiException e) {
            err.println(e.getMessage());
            e.shortfalls()
                    .forEach(s -> err.printf("  %-24s wanted %d, have %d%n", s.sku(), s.requested(), s.available()));
            return 1;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println(e.getMessage());
            return 1;
        }
    }

    private static int dispatch(
            List<String> positional,
            Map<String, String> flags,
            TillClient client,
            IdempotencyKey key,
            PrintStream out,
            PrintStream err) {
        String command = positional.get(0);
        List<String> rest = positional.subList(1, positional.size());
        switch (command) {
            case "stock" -> {
                require(rest, 1, "stock <sku>", err);
                TillClient.StockView stock = client.stock(Sku.of(rest.get(0)));
                printStock(out, stock);
            }
            case "adjust" -> {
                require(rest, 2, "adjust <sku> <delta>", err);
                printStock(out, client.adjust(key, Sku.of(rest.get(0)), Long.parseLong(rest.get(1))));
            }
            case "reserve" -> {
                if (rest.isEmpty()) {
                    err.println("usage: reserve <sku>:<quantity> [<sku>:<quantity> ...]");
                    return 2;
                }
                List<Line> lines = rest.stream().map(Tillctl::parseLine).toList();
                Duration ttl = flags.containsKey("ttl") ? Duration.ofSeconds(Long.parseLong(flags.get("ttl"))) : null;
                Outcome.Reserved reserved = client.reserve(key, lines, ttl);
                out.printf(
                        "%s  expires %s  %s%n",
                        reserved.id(),
                        reserved.expiresAt(),
                        reserved.lines().stream().map(Line::toString).reduce((a, b) -> a + " " + b).orElse(""));
            }
            case "commit" -> {
                require(rest, 1, "commit <reservation-id>", err);
                Outcome.Committed committed = client.commit(key, ReservationId.of(rest.get(0)));
                out.printf("committed %s at %s%n", committed.id(), committed.at());
            }
            case "release" -> {
                require(rest, 1, "release <reservation-id>", err);
                Outcome.Released released = client.release(key, ReservationId.of(rest.get(0)));
                out.printf("released %s at %s%n", released.id(), released.at());
            }
            case "get" -> {
                require(rest, 1, "get <reservation-id>", err);
                TillClient.ReservationView view = client.reservation(ReservationId.of(rest.get(0)));
                out.printf(
                        "%s  %s%s  created %s  expires %s  %s%n",
                        view.id(),
                        view.effectiveState(),
                        view.state() == view.effectiveState() ? "" : " (stored " + view.state() + ")",
                        view.createdAt(),
                        view.expiresAt(),
                        view.lines().stream().map(Line::toString).reduce((a, b) -> a + " " + b).orElse(""));
            }
            default -> {
                err.println("unknown command '" + command + "'");
                usage(err);
                return 2;
            }
        }
        return 0;
    }

    private static void printStock(PrintStream out, TillClient.StockView stock) {
        out.printf(
                "%-24s onHand=%-8d reserved=%-8d available=%d%n",
                stock.sku(), stock.onHand(), stock.reserved(), stock.available());
    }

    private static Line parseLine(String argument) {
        int colon = argument.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("expected <sku>:<quantity>, got '" + argument + "'");
        }
        return new Line(Sku.of(argument.substring(0, colon)), Long.parseLong(argument.substring(colon + 1)));
    }

    private static void require(List<String> arguments, int count, String usage, PrintStream err) {
        if (arguments.size() < count) {
            err.println("usage: " + usage);
            throw new IllegalArgumentException("expected " + count + " argument(s)");
        }
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static void usage(PrintStream out) {
        out.println(
                """
                tillctl — a command line for a till service

                  stock   <sku>                         read a level
                  adjust  <sku> <delta>                 change on-hand stock (needs the admin token)
                  reserve <sku>:<qty> [...] [--ttl=s]   take a hold
                  commit  <reservation-id>              turn a hold into a sale
                  release <reservation-id>              give a hold back
                  get     <reservation-id>              look a hold up

                  --url=      service base URL, or TILL_URL (default http://127.0.0.1:8080)
                  --token=    bearer token, or TILL_TOKEN
                  --key=      idempotency key; generated per invocation if omitted. A script that
                              retries must pass a fixed one, or the retry takes a second hold.
                  --ttl=      hold duration in seconds, for reserve

                Exit codes: 0 success, 1 the service refused, 2 a usage error.""");
    }
}
