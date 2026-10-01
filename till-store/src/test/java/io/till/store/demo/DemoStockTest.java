package io.till.store.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.till.client.TillClient;
import io.till.store.StoreProperties;
import io.till.store.StoreTest;
import io.till.store.catalogue.Games;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("the demonstration's stock")
class DemoStockTest extends StoreTest {

    @Autowired
    Games games;

    @Autowired
    StoreProperties properties;

    @Test
    @DisplayName("stocks every game, splits each as many ways as asked, and a second pass changes nothing")
    void stocksAndSplits() {
        List<String> skus = games.skus();
        String unstocked = skus.get(0);
        DemoStock demo = new DemoStock(with(new StoreProperties.Demo(true, 10, Map.of(unstocked, 0L), 4)), games, ledger);

        demo.stockEveryGame();
        demo.stockEveryGame();

        List<TillClient.StockView> levels = ledger.stock(100, null).items();
        assertThat(levels).hasSize(skus.size() - 1);
        assertThat(levels).noneMatch(level -> level.sku().value().equals(unstocked));
        assertThat(levels).allSatisfy(level -> {
            assertThat(level.onHand()).as("stocked once, not twice").isEqualTo(10);
            assertThat(level.shards()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("left at one row, splits nothing")
    void leavesRowsAlone() {
        new DemoStock(with(new StoreProperties.Demo(true, 10, Map.of(), 1)), games, ledger).stockEveryGame();

        assertThat(ledger.stock(100, null).items()).allSatisfy(level -> assertThat(level.shards()).isEqualTo(1));
    }

    private StoreProperties with(StoreProperties.Demo demo) {
        return new StoreProperties(properties.till(), properties.kafka(), properties.auth(), properties.checkout(), demo,
                properties.catalogue(), properties.sales());
    }
}
