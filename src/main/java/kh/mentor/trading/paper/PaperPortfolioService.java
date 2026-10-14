package kh.mentor.trading.paper;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import kh.mentor.trading.risk.RiskPlan;
import kh.mentor.trading.risk.RiskPlanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manually approved educational paper ledger.
 * No broker API, external orders, or AI-controlled execution.
 * Lock the singleton account row to serialize mutations.
 */
@Service
public class PaperPortfolioService {
    private static final Set<String> SYMBOLS = Set.of("BTCUSDT", "ETHUSDT");
    private static final Set<String> EMOTIONS =
        Set.of("CALM", "FOMO", "FEAR", "IMPULSIVE", "UNCERTAIN");
    private static final Set<String> EXIT_REASONS = Set.of("MANUAL", "STOP", "TARGET");
    private final JdbcTemplate jdbc;
    private final RiskPlanner risk;
    private final double sideCost;

    public PaperPortfolioService(JdbcTemplate jdbc, RiskPlanner risk,
        @Value("${trading.fee-per-side:0.001}") double fee,
        @Value("${trading.slippage-per-side:0.001}") double slippage) {
        if (!Double.isFinite(fee) || !Double.isFinite(slippage)
            || fee < 0 || slippage < 0 || fee > .01 || slippage > .02) {
            throw new IllegalArgumentException("Invalid paper costs");
        }
        this.jdbc=jdbc;
        this.risk=risk;
        this.sideCost=fee+slippage;
    }

    @Transactional
    public PaperPosition open(OpenPaperRequest input) {
        if (input == null || !input.confirmPaperOnly() ||
            !SYMBOLS.contains(input.symbol()) || !validNote(input.thesis(), 500) ||
            !EMOTIONS.contains(input.emotion())) {
            throw new IllegalArgumentException("Manual paper approval, supported pair and valid journal required");
        }
        String requestId=uuid(input.requestId());
        // Account mutex provides transaction serialization for cash/risk/idempotency.
        double cash=lockedCash();
        List<PaperPosition> existing=jdbc.query("""
            SELECT * FROM paper_positions WHERE request_id=?
            """, this::mapPosition,requestId);
        if (!existing.isEmpty()) {
            PaperPosition previous=existing.getFirst();
            if (!previous.symbol().equals(input.symbol())
                || Double.compare(previous.entryPrice(),input.entryPrice())!=0
                || Double.compare(previous.stopPrice(),input.stopPrice())!=0
                || Double.compare(previous.targetPrice(),input.targetPrice())!=0) {
                throw new IllegalArgumentException("Request id reused for different paper trade");
            }
            return previous;
        }
        Integer opened=jdbc.queryForObject(
            "SELECT COUNT(*) FROM paper_positions WHERE status='OPEN'",Integer.class);
        if (opened!=null && opened>=2) {
            throw new IllegalArgumentException("Maximum two simultaneous paper positions");
        }
        double starting=startingBalance();
        long now=System.currentTimeMillis();
        LocalDate today=Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate();
        long todayMs=today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long weekMs=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        // Loss limits are conservative: sum of realized losing trades, not net wins.
        if (realizedLossSince(todayMs)>=starting*.02
            || realizedLossSince(weekMs)>=starting*.05) {
            throw new IllegalArgumentException("Paper daily or weekly loss limit reached");
        }
        RiskPlan plan=risk.calculate(cash,input.entryPrice(),input.stopPrice(),input.targetPrice());
        if (!plan.valid() || plan.quantity()<=0) {
            throw new IllegalArgumentException("Proposed paper trade fails risk/reward checks");
        }
        double entryCash=plan.quantity()*input.entryPrice()*(1+sideCost);
        if (entryCash>cash+1e-7) throw new IllegalArgumentException("Insufficient virtual cash");
        String id=UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO paper_positions
              (id,request_id,symbol,entry_price,stop_price,target_price,quantity,
               entry_cash_usdt,opened_at_ms,status,thesis,emotion)
            VALUES (?,?,?,?,?,?,?,?,?,'OPEN',?,?)
            """,id,requestId,input.symbol(),input.entryPrice(),input.stopPrice(),
            input.targetPrice(),plan.quantity(),entryCash,now,
            input.thesis().trim(),input.emotion());
        jdbc.update("UPDATE paper_account SET cash_usdt=cash_usdt-? WHERE id=1",entryCash);
        return get(id);
    }

    @Transactional
    public PaperPosition close(String id, ClosePaperRequest input) {
        if (input==null || !input.confirmPaperOnly()
            || !Double.isFinite(input.exitPrice()) || input.exitPrice()<=0
            || !EXIT_REASONS.contains(input.exitReason())
            || !validNote(input.reflection(),2000)) {
            throw new IllegalArgumentException("Manual paper closure and reflection required");
        }
        lockedCash();
        PaperPosition position=get(uuid(id));
        if (position.status().equals("CLOSED")) return position;
        double proceeds=position.quantity()*input.exitPrice()*(1-sideCost);
        double pnl=proceeds-position.entryCashUsdt();
        long now=System.currentTimeMillis();
        int count=jdbc.update("""
            UPDATE paper_positions SET status='CLOSED',close_price=?,
              close_cash_usdt=?,net_pnl_usdt=?,closed_at_ms=?,
              exit_reason=?,reflection=? WHERE id=? AND status='OPEN'
            """,input.exitPrice(),proceeds,pnl,now,input.exitReason(),
            input.reflection().trim(),position.id());
        if (count!=1) throw new IllegalStateException("Paper close conflict");
        jdbc.update("UPDATE paper_account SET cash_usdt=cash_usdt+? WHERE id=1",proceeds);
        return get(position.id());
    }

    @Transactional(readOnly=true)
    public PaperPosition get(String id) {
        List<PaperPosition> rows=jdbc.query(
            "SELECT * FROM paper_positions WHERE id=?",this::mapPosition,uuid(id));
        if (rows.isEmpty()) throw new IllegalArgumentException("Unknown paper position");
        return rows.getFirst();
    }

    @Transactional(readOnly=true)
    public List<PaperPosition> recent() {
        return jdbc.query("SELECT * FROM paper_positions ORDER BY opened_at_ms DESC LIMIT 100",
            this::mapPosition);
    }

    @Transactional(readOnly=true)
    public PaperSummary summary() {
        double starting=startingBalance();
        double cash=jdbc.queryForObject(
            "SELECT cash_usdt FROM paper_account WHERE id=1",Double.class);
        double reserved=jdbc.queryForObject("""
            SELECT COALESCE(SUM(entry_cash_usdt),0) FROM paper_positions WHERE status='OPEN'
            """,Double.class);
        int opened=jdbc.queryForObject(
            "SELECT COUNT(*) FROM paper_positions WHERE status='OPEN'",Integer.class);
        int closed=jdbc.queryForObject(
            "SELECT COUNT(*) FROM paper_positions WHERE status='CLOSED'",Integer.class);
        double pnl=jdbc.queryForObject("""
            SELECT COALESCE(SUM(net_pnl_usdt),0) FROM paper_positions WHERE status='CLOSED'
            """,Double.class);
        return new PaperSummary(starting,cash,reserved,opened,closed,pnl,
            "Hypothetical user-entered prices, not verified fills; open P&L not marked to market");
    }

    private double lockedCash() {
        Double cash=jdbc.queryForObject(
            "SELECT cash_usdt FROM paper_account WHERE id=1 FOR UPDATE",Double.class);
        if(cash==null)throw new IllegalStateException("Paper account not initialized");
        return cash;
    }

    private double startingBalance() {
        return jdbc.queryForObject(
            "SELECT starting_balance_usdt FROM paper_account WHERE id=1",Double.class);
    }

    private double realizedLossSince(long startMs) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(CASE WHEN net_pnl_usdt<0 THEN -net_pnl_usdt ELSE 0 END),0)
            FROM paper_positions WHERE status='CLOSED' AND closed_at_ms>=?
            """,Double.class,startMs);
    }

    private PaperPosition mapPosition(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new PaperPosition(rs.getString("id"),rs.getString("request_id"),rs.getString("symbol"),
            rs.getDouble("entry_price"),rs.getDouble("stop_price"),rs.getDouble("target_price"),
            rs.getDouble("quantity"),rs.getDouble("entry_cash_usdt"),rs.getLong("opened_at_ms"),
            rs.getString("status"), (Double)rs.getObject("close_price"),
            (Double)rs.getObject("close_cash_usdt"),(Double)rs.getObject("net_pnl_usdt"),
            (Long)rs.getObject("closed_at_ms"),rs.getString("thesis"),
            rs.getString("emotion"),rs.getString("exit_reason"),rs.getString("reflection"));
    }

    private static boolean validNote(String s,int max) {
        return s!=null && !s.isBlank() && s.length()<=max;
    }
    private static String uuid(String value) {
        try { return UUID.fromString(value).toString(); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid UUID",e); }
    }
}
