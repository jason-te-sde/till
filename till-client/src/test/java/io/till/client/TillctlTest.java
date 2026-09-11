package io.till.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TillctlTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @Test
    @DisplayName("a stock level prints aligned, so a column of them reads")
    void stock() {
        try (StubTill stub =
                new StubTill().always(200, "{\"sku\":\"widget\",\"onHand\":100,\"reserved\":2,\"available\":98}")) {
            assertEquals(0, run(stub, "stock", "widget"));

            assertTrue(stdout().contains("widget"), stdout());
            assertTrue(stdout().contains("available=98"), stdout());
        }
    }

    @Test
    @DisplayName("a hold prints its id, its deadline and what it holds")
    void reserve() {
        String body =
                """
                {"id":"r-1","lines":[{"sku":"widget","quantity":2}],"expiresAt":"2026-09-10T12:15:00Z"}""";
        try (StubTill stub = new StubTill().always(201, body)) {
            assertEquals(0, run(stub, "reserve", "widget:2", "--ttl=900"));

            assertTrue(stdout().contains("r-1"), stdout());
            assertTrue(stdout().contains("widgetx2"), stdout());
            assertTrue(stub.requests().get(0).body().contains("\"ttlSeconds\":900"));
        }
    }

    @Test
    @DisplayName("--key is passed through, which is how a script retries safely")
    void explicitKey() {
        try (StubTill stub = new StubTill().always(200, "{\"id\":\"r-1\",\"committedAt\":\"2026-09-10T12:00:00Z\"}")) {
            assertEquals(0, run(stub, "commit", "r-1", "--key=pay-8123"));

            assertEquals("pay-8123", stub.requests().get(0).idempotencyKey());
        }
    }

    @Test
    @DisplayName("a key is generated when none is given, so an interactive call cannot collide")
    void generatedKey() {
        try (StubTill stub = new StubTill().always(200, "{\"id\":\"r-1\",\"releasedAt\":\"2026-09-10T12:00:00Z\"}")) {
            assertEquals(0, run(stub, "release", "r-1"));

            assertTrue(stub.requests().get(0).idempotencyKey().startsWith("tillctl-"));
        }
    }

    @Test
    @DisplayName("a refusal exits 1 and prints every shortfall")
    void refusal() {
        String body =
                """
                {"detail":"not enough stock for widget","code":"INSUFFICIENT_STOCK",
                 "shortfalls":[{"sku":"widget","requested":5,"available":2}]}""";
        try (StubTill stub = new StubTill().always(409, body)) {
            assertEquals(1, run(stub, "reserve", "widget:5"));

            assertTrue(stderr().contains("INSUFFICIENT_STOCK"), stderr());
            assertTrue(stderr().contains("wanted 5, have 2"), stderr());
        }
    }

    @Test
    @DisplayName("a usage mistake exits 2, which is not the same as being refused")
    void usageErrors() {
        assertEquals(2, Tillctl.run(new String[] {}, print(out), print(err)));
        assertTrue(stdout().contains("tillctl"), stdout());

        assertEquals(2, Tillctl.run(new String[] {"teleport"}, print(out), print(err)));
        assertTrue(stderr().contains("unknown command"), stderr());

        assertEquals(2, Tillctl.run(new String[] {"stock"}, print(out), print(err)));
        assertTrue(stderr().contains("usage: stock"), stderr());
    }

    @Test
    @DisplayName("--help exits 0, because asking for help is not an error")
    void help() {
        assertEquals(0, Tillctl.run(new String[] {"stock", "--help"}, print(out), print(err)));
        assertTrue(stdout().contains("tillctl"), stdout());
    }

    @Test
    @DisplayName("a malformed line argument says what was expected")
    void badLine() {
        try (StubTill stub = new StubTill().always(201, "{}")) {
            assertEquals(2, run(stub, "reserve", "widget"));

            assertTrue(stderr().contains("<sku>:<quantity>"), stderr());
        }
    }

    private int run(StubTill stub, String... args) {
        String[] all = new String[args.length + 1];
        System.arraycopy(args, 0, all, 0, args.length);
        all[args.length] = "--url=" + stub.url();
        return Tillctl.run(all, print(out), print(err));
    }

    private static PrintStream print(ByteArrayOutputStream sink) {
        return new PrintStream(sink, true, StandardCharsets.UTF_8);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String stderr() {
        return err.toString(StandardCharsets.UTF_8);
    }
}
