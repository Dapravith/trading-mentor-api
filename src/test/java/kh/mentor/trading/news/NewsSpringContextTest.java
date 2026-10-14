package kh.mentor.trading.news;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Verifies real Spring dependency wiring, with no Telegram/market network I/O. */
@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:news-context;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always",
    "trading.news.intake-enabled=true",
    "trading.news.ingest-key=deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef",
    "trading.alert.delivery-enabled=false",
    "trading.scan.scheduling-enabled=false"
})
class NewsSpringContextTest {
    @Autowired NewsIntakeService service;
    @Autowired HermesNewsController controller;

    @Test void newsModuleBeansAreWiredWithoutRealNetworkAccess() {
        assertNotNull(service);
        assertNotNull(controller);
    }
}
