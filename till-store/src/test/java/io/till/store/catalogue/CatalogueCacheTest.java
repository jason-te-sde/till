package io.till.store.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.till.store.StoreProperties;
import io.till.store.StoreTest;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("the catalogue's cache")
class CatalogueCacheTest extends StoreTest {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {};

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JsonMapper json;

    @Autowired
    StoreProperties properties;

    @Autowired
    ObjectProvider<Flyway> flyway;

    @Test
    @DisplayName("answers a repeated search from Valkey, and says so in its timings")
    void repeats() throws Exception {
        double before = reads("cache");
        mvc.perform(get("/api/games").param("genre", "Puzzle")).andExpect(status().isOk());
        assertThat(reads("cache")).isEqualTo(before);

        mvc.perform(get("/api/games").param("genre", "Puzzle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].genre").value("Puzzle"));
        assertThat(reads("cache")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("keeps a game's page, never its availability")
    void availabilityStaysLive() throws Exception {
        ledger.stock("sunless-orbit", 5);
        ledger.deliver(projector);
        mvc.perform(get("/api/games/sunless-orbit")).andExpect(jsonPath("$.game.available").value(5));

        ledger.stock("sunless-orbit", 4);
        ledger.deliver(projector);
        double before = reads("cache");
        mvc.perform(get("/api/games/sunless-orbit")).andExpect(jsonPath("$.game.available").value(9));
        assertThat(reads("cache")).as("the game itself came from the cache").isGreaterThan(before);
    }

    @Test
    @DisplayName("keeps nothing for a game that does not exist")
    void noNegativeEntries() throws Exception {
        mvc.perform(get("/api/games/no-such-game")).andExpect(status().isNotFound());
        mvc.perform(get("/api/games/no-such-game")).andExpect(status().isNotFound());
        assertThat(keys("*no-such-game*")).isEmpty();
    }

    @Test
    @DisplayName("keys carry the schema version, so a release that changes the catalogue reads none of the old answers")
    void keysAreVersioned() throws Exception {
        mvc.perform(get("/api/genres")).andExpect(status().isOk());
        String version = flyway.getObject().info().current().getVersion().getVersion();
        assertThat(keys("*genres*")).singleElement().asString().startsWith("till:store:catalogue:v" + version + ":stable:");
    }

    @Test
    @DisplayName("spreads each expiry by up to the jitter, either way")
    void expiriesAreSpread() throws Exception {
        mvc.perform(get("/api/genres")).andExpect(status().isOk());
        long seconds = redis.getExpire(keys("*genres*").iterator().next(), TimeUnit.SECONDS);
        long stable = properties.catalogue().cache().stable().toSeconds();
        assertThat(seconds).isBetween((long) (stable * 0.9) - 1, (long) (stable * 1.1));

        CatalogueCache cache = catalogueCache;
        Set<Duration> lifetimes = new java.util.HashSet<>();
        for (int i = 0; i < 50; i++) {
            lifetimes.add(cache.lifetime(CatalogueCache.Region.SEARCHES));
        }
        assertThat(lifetimes).as("fifty lifetimes that are not all the same").hasSizeGreaterThan(10);
        assertThat(lifetimes).allSatisfy(lifetime -> assertThat(lifetime).isBetween(Duration.ofSeconds(54), Duration.ofSeconds(66)));
    }

    @Test
    @DisplayName("asks the database once when a crowd misses the same answer together")
    void oneLoadPerMiss() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch everyoneWaiting = new CountDownLatch(1);
        try (ExecutorService crowd = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<String>>> answers = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                answers.add(crowd.submit(() -> catalogueCache.get(CatalogueCache.Region.SEARCHES, "crowd", STRINGS, () -> {
                    loads.incrementAndGet();
                    await(everyoneWaiting);
                    return List.of("answer");
                })));
            }
            // Long enough for all fifty to have missed and queued behind the first.
            Thread.sleep(300);
            everyoneWaiting.countDown();
            for (Future<List<String>> answer : answers) {
                assertThat(answer.get(5, TimeUnit.SECONDS)).containsExactly("answer");
            }
        }
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("treats an entry it cannot read as a miss, and replaces it")
    void unreadableIsAMiss() {
        List<String> first = catalogueCache.get(CatalogueCache.Region.STABLE, "shape", STRINGS, () -> List.of("a"));
        String key = keys("*:shape").iterator().next();
        redis.opsForValue().set(key, "{not json");

        List<String> again = catalogueCache.get(CatalogueCache.Region.STABLE, "shape", STRINGS, () -> List.of("b"));
        assertThat(first).containsExactly("a");
        assertThat(again).containsExactly("b");
        assertThat(redis.opsForValue().get(key)).isEqualTo("[\"b\"]");
    }

    @Test
    @DisplayName("without Valkey, answers from the database rather than failing")
    void withoutValkey() {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        nowhere.afterPropertiesSet();
        nowhere.start();
        try {
            CatalogueCache cache = new CatalogueCache(
                    new StringRedisTemplate(nowhere), json, properties, flyway, new SimpleMeterRegistry());
            try {
                assertThat(cache.get(CatalogueCache.Region.STABLE, "down", STRINGS, () -> List.of("from the database")))
                        .containsExactly("from the database");
            } finally {
                cache.stop();
            }
        } finally {
            nowhere.destroy();
        }
    }

    @Test
    @DisplayName("switched off, asks the database every time")
    void switchedOff() {
        StoreProperties off = new StoreProperties(
                properties.till(), properties.kafka(), properties.auth(), properties.checkout(), properties.demo(),
                new StoreProperties.Catalogue(new StoreProperties.Cache(false, Duration.ofMinutes(10), Duration.ofSeconds(60),
                        Duration.ofSeconds(60), 0.1)),
                properties.sales());
        CatalogueCache cache = new CatalogueCache(redis, json, off, flyway, new SimpleMeterRegistry());
        try {
            AtomicInteger loads = new AtomicInteger();
            for (int i = 0; i < 3; i++) {
                cache.get(CatalogueCache.Region.STABLE, "off", STRINGS, () -> List.of(String.valueOf(loads.incrementAndGet())));
            }
            assertThat(loads).hasValue(3);
            assertThat(keys("*:off")).isEmpty();
        } finally {
            cache.stop();
        }
    }

    private double reads(String source) {
        return meters.find("store.catalogue.reads").tag("source", source).timers().stream().mapToDouble(Timer::count).sum();
    }

    private Set<String> keys(String pattern) {
        return redis.keys("till:store:catalogue:" + pattern);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
