package io.till.store.catalogue;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.till.store.StoreProperties;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The catalogue's answers, kept in Valkey for as long as they can be trusted — so that a crowd
 * browsing the same pages asks the database about them once a minute rather than once a request.
 *
 * <p>Why there is one: the second load test (docs/load-test.md) found the catalogue's searches and tag
 * counts taking more than half of the database's CPU, and a checkout waiting behind them for one of
 * the store's connections until it gave up. The edge's five-second cache was already in front; what
 * it could not do is share, between two edges and two stores, the answer to a search nobody had made
 * in the last five seconds.
 *
 * <p><b>How long, by what the answer is.</b> A game, the featured and newest rows and the genres change
 * only when a release changes the catalogue, and are kept for {@code stable} (ten minutes). Best
 * sellers and the related games ranked by them move with every sale, and are kept for {@code sales}
 * (a minute): "best this week" a minute late is still true. Searches are the long tail — every
 * spelling a new entry — and are kept for {@code searches} (a minute), so the tail stays small. Every
 * expiry is moved by up to {@code jitter} either way, so that answers written together, as they are
 * when the cache is cold, do not all expire together and send the crowd back to the database at once.
 *
 * <p><b>A release changes every key.</b> Keys carry the schema version, so a deployment whose
 * migrations change the catalogue reads none of the answers the previous one wrote, and no entry has
 * to be found and deleted.
 *
 * <p><b>One question to the database at a time.</b> When an answer is missing, one caller in this
 * process asks the database and the others that want the same answer wait for it, rather than a
 * hundred of them asking at once the moment it expires.
 *
 * <p><b>Two things it does not do.</b> It does not cache availability: that is the one part of a page
 * that has to be fresh, and it is one indexed lookup. And it does not price an order, which is always
 * done from the database ({@code OrderService}).
 *
 * <p><b>Without Valkey the store is slower, not down.</b> A read or write that fails is a miss, and the
 * answer comes from the database; it says so in the log, at most once a minute.
 *
 * <p>Every read is timed, by where the answer came from, as {@code store.catalogue.reads}; once a
 * minute the last minute's counts and times are logged as a {@code catalogue-reads} line, which is
 * how a load test reads the average without reaching into a running store.
 */
@Component
public class CatalogueCache {

    /** How long an answer can be trusted. */
    public enum Region {
        STABLE,
        SALES,
        SEARCHES
    }

    /** Longest key worth caching under. A made-up genre a kilobyte long is somebody's experiment. */
    static final int MAX_KEY = 512;

    private static final Logger LOG = LoggerFactory.getLogger(CatalogueCache.class);
    private static final long COMPLAIN_EVERY_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final StoreProperties.Cache settings;
    private final String prefix;
    private final Map<Region, Duration> lifetimes = new EnumMap<>(Region.class);
    private final Map<String, CompletableFuture<Object>> loading = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new LinkedHashMap<>();
    private final AtomicLong lastComplaint = new AtomicLong(System.nanoTime() - COMPLAIN_EVERY_NANOS);
    private final ScheduledExecutorService minutes;
    private final Map<String, double[]> lastMinute = new LinkedHashMap<>();

    CatalogueCache(
            StringRedisTemplate redis,
            JsonMapper json,
            StoreProperties properties,
            ObjectProvider<Flyway> flyway,
            MeterRegistry meters) {
        this.redis = redis;
        this.json = json;
        this.settings = properties.catalogue().cache();
        this.prefix = "till:store:catalogue:" + schemaVersion(flyway) + ":";
        lifetimes.put(Region.STABLE, settings.stable());
        lifetimes.put(Region.SALES, settings.sales());
        lifetimes.put(Region.SEARCHES, settings.searches());
        for (Region region : Region.values()) {
            for (String source : List.of("cache", "database")) {
                timers.put(timerKey(region, source), Timer.builder("store.catalogue.reads")
                        .description("How long the storefront waited for a catalogue answer, by where it came from")
                        .tag("region", region.name().toLowerCase(Locale.ROOT))
                        .tag("source", source)
                        .register(meters));
            }
        }
        for (String source : List.of("cache", "database")) {
            lastMinute.put(source, new double[2]);
        }
        this.minutes = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("catalogue-reads").factory());
        minutes.scheduleAtFixedRate(this::logMinute, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * An answer: from the cache if it has one, otherwise from {@code load}, which is then kept.
     *
     * @param region how long the answer can be trusted
     * @param key what was asked, unique within the region
     * @param type what the answer is, for reading it back
     * @param load how to ask the database; an answer of {@code null} is returned and not kept
     * @param <T> the answer's type
     * @return the answer
     */
    public <T> T get(Region region, String key, TypeReference<T> type, Supplier<T> load) {
        long started = System.nanoTime();
        if (!settings.enabled() || key.length() > MAX_KEY) {
            T answer = load.get();
            record(region, "database", started);
            return answer;
        }
        String redisKey = prefix + region.name().toLowerCase(Locale.ROOT) + ":" + key;
        String kept = read(redisKey);
        if (kept != null) {
            try {
                T answer = json.readValue(kept, type);
                record(region, "cache", started);
                return answer;
            } catch (JacksonException e) {
                // Written by a build that disagrees with this one about the answer's shape: a miss.
                complain("a catalogue answer that could not be read back", e);
            }
        }
        T answer = once(redisKey, region, load);
        record(region, "database", started);
        return answer;
    }

    /** Forgets every answer this build has kept. For tests, which change the sales between them. */
    public void clear() {
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(1000).build())) {
            cursor.forEachRemaining(keys::add);
        }
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @PreDestroy
    void stop() {
        minutes.shutdownNow();
    }

    /** One caller per key asks the database; the others that want the same answer wait for it. */
    private <T> T once(String redisKey, Region region, Supplier<T> load) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> running = loading.putIfAbsent(redisKey, mine);
        if (running != null) {
            try {
                return cast(running.join());
            } catch (CompletionException e) {
                throw e.getCause() instanceof RuntimeException cause ? cause : e;
            }
        }
        try {
            T answer = load.get();
            if (answer != null) {
                write(redisKey, answer, region);
            }
            mine.complete(answer);
            return answer;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            loading.remove(redisKey, mine);
        }
    }

    private String read(String redisKey) {
        try {
            return redis.opsForValue().get(redisKey);
        } catch (RuntimeException e) {
            complain("the catalogue cache could not be read", e);
            return null;
        }
    }

    private void write(String redisKey, Object answer, Region region) {
        try {
            redis.opsForValue().set(redisKey, json.writeValueAsString(answer), lifetime(region));
        } catch (RuntimeException e) {
            complain("the catalogue cache could not be written", e);
        }
    }

    /** The region's lifetime, moved by up to the jitter either way. */
    Duration lifetime(Region region) {
        long millis = lifetimes.get(region).toMillis();
        double spread = settings.jitter() * (2 * ThreadLocalRandom.current().nextDouble() - 1);
        return Duration.ofMillis(Math.max(1, Math.round(millis * (1 + spread))));
    }

    private void record(Region region, String source, long started) {
        timers.get(timerKey(region, source)).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    /**
     * The last minute's reads, one line: how many came from each source and how long they took in
     * all. A load test adds these up over its steady window.
     */
    private void logMinute() {
        try {
            Map<String, Object> minute = new LinkedHashMap<>();
            for (String source : List.of("cache", "database")) {
                long count = 0;
                double totalMs = 0;
                for (Region region : Region.values()) {
                    Timer timer = timers.get(timerKey(region, source));
                    count += timer.count();
                    totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
                }
                double[] before = lastMinute.get(source);
                Map<String, Object> reads = new LinkedHashMap<>();
                reads.put("count", count - (long) before[0]);
                reads.put("total_ms", Math.round((totalMs - before[1]) * 10) / 10.0);
                minute.put(source, reads);
                before[0] = count;
                before[1] = totalMs;
            }
            LOG.info("catalogue-reads {}", json.writeValueAsString(minute));
        } catch (RuntimeException e) {
            LOG.warn("could not summarise the last minute's catalogue reads", e);
        }
    }

    private void complain(String what, Exception e) {
        long now = System.nanoTime();
        long last = lastComplaint.get();
        if (now - last >= COMPLAIN_EVERY_NANOS && lastComplaint.compareAndSet(last, now)) {
            LOG.warn("{}; answering from the database meanwhile: {}", what, e.toString());
        }
    }

    private static String timerKey(Region region, String source) {
        return region + "/" + source;
    }

    private static String schemaVersion(ObjectProvider<Flyway> flyway) {
        Flyway migrations = flyway.getIfAvailable();
        MigrationInfo current = migrations == null ? null : migrations.info().current();
        return current == null || current.getVersion() == null ? "none" : "v" + current.getVersion().getVersion();
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object answer) {
        return (T) answer;
    }
}
