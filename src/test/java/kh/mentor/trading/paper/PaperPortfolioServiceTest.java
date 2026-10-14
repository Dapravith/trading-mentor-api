package kh.mentor.trading.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:paper-service-tests;DB_CLOSE_DELAY=-1",
    "spring.sql.init.mode=always"
})
@Transactional // Roll back each simulated portfolio mutation after every test.
class PaperPortfolioServiceTest {
    @Autowired PaperPortfolioService service;

    private OpenPaperRequest buy() {
        return new OpenPaperRequest(UUID.randomUUID().toString(),"BTCUSDT",
            100,98,110,true,"Pullback in a tested paper setup","CALM");
    }

    @Test void paperEntryRequiresExplicitApproval() {
        OpenPaperRequest request=new OpenPaperRequest(UUID.randomUUID().toString(),
            "BTCUSDT",100,98,110,false,"Test setup","CALM");
        assertThrows(IllegalArgumentException.class,()->service.open(request));
        assertEquals(1000,service.summary().availableCashUsdt(),0.000001);
    }

    @Test void duplicateRequestDoesNotSpendCashTwice() {
        OpenPaperRequest request=buy();
        PaperPosition first=service.open(request);
        double remaining=service.summary().availableCashUsdt();
        PaperPosition repeat=service.open(request);
        assertEquals(first.id(),repeat.id());
        assertEquals(remaining,service.summary().availableCashUsdt(),1e-6);
        assertEquals(1,service.summary().openPositions());
    }

    @Test void closingRestoresVirtualCashAndRecordsJournal() {
        PaperPosition entry=service.open(buy());
        assertTrue(entry.quantity()>0);
        assertTrue(service.summary().availableCashUsdt()<1000);
        PaperPosition closed=service.close(entry.id(),
            new ClosePaperRequest(110,"TARGET","Waited for rules",true));
        assertEquals("CLOSED",closed.status());
        assertTrue(closed.netPnlUsdt()>0);
        assertEquals("Waited for rules",closed.reflection());
        assertEquals(0,service.summary().openPositions());
        assertEquals(1,service.summary().closedPositions());
        assertEquals(1000+closed.netPnlUsdt(),
            service.summary().availableCashUsdt(),1e-6);
        assertEquals(closed.netPnlUsdt(),
            service.close(entry.id(),
                new ClosePaperRequest(110,"TARGET","Waited for rules",true)).netPnlUsdt(),1e-6);
    }

    @Test void concurrentPositionLimitEnforced() {
        service.open(buy());
        service.open(buy());
        assertThrows(IllegalArgumentException.class,()->service.open(buy()));
        assertEquals(2,service.summary().openPositions());
    }

    @Test void hittingDailyLossLimitBlocksNewPaperEntries() {
        PaperPosition entry=service.open(buy());
        service.close(entry.id(),
            new ClosePaperRequest(1,"STOP","Wrong model; paper loss",true));
        assertThrows(IllegalArgumentException.class,()->service.open(buy()));
    }

    @Test void invalidRiskSetupIsRejected() {
        assertThrows(IllegalArgumentException.class,()->service.open(
            new OpenPaperRequest(UUID.randomUUID().toString(),
                "BTCUSDT",100,105,110,true,"Unsafe stop","FOMO")));
        assertEquals(0,service.summary().openPositions());
    }
}
