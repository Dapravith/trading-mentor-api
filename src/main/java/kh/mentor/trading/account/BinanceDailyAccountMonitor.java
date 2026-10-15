package kh.mentor.trading.account;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Daily *private* read-only health check. Exactly one sender per H2 database.
 * Never sends balances, credentials, UID, real order instructions or messages
 * to the public alert channel.
 */
@Component
@ConditionalOnProperty(prefix="trading.account",
    name={"enabled","daily-enabled"},havingValue="true")
public class BinanceDailyAccountMonitor {
    private static final Logger log=LoggerFactory.getLogger(BinanceDailyAccountMonitor.class);
    private static final ZoneId ICT=ZoneId.of("Asia/Phnom_Penh");
    private final BinanceSpotAccountReader reader;
    private final JdbcTemplate jdbc;
    private final String botToken;
    private final String privateChatId;
    private final JsonMapper mapper;
    private final HttpClient http=HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    public BinanceDailyAccountMonitor(BinanceSpotAccountReader reader,JdbcTemplate jdbc,
        JsonMapper mapper,
        @Value("${trading.account.daily-bot-token:}") String token,
        @Value("${trading.account.daily-private-chat-id:}") String chat) {
        if(token.isBlank() || !chat.matches("[1-9][0-9]{3,19}")) {
            throw new IllegalStateException("Daily Binance report needs bot token and a positive private-user chat ID");
        }
        this.reader=reader;
        this.jdbc=jdbc;
        this.mapper=mapper;
        this.botToken=token;
        this.privateChatId=chat;
    }

    @Scheduled(cron="${trading.account.daily-cron:0 0 9 * * *}",zone="Asia/Phnom_Penh")
    public void checkDaily() {
        String date=LocalDate.now(ICT).toString();
        Integer present=jdbc.queryForObject("""
            SELECT COUNT(*) FROM binance_account_digest WHERE report_date=? AND status='SENT'
            """,Integer.class,date);
        if(present!=null && present>0) return;
        try {
            var result=reader.check();
            // Non-sensitive boolean health summary, never raw asset quantities.
            String msg="🔐 DAILY BINANCE SPOT ACCOUNT CHECK\n"
                +"Date: "+date+" (Cambodia)\n"
                +"Read-only API: VERIFIED\n"
                +"Non-zero asset types: "+result.nonZeroAssetCount()+"\n"
                +"BTC held: "+yesNo(result.holdsBtc())+" | ETH held: "+yesNo(result.holdsEth())+"\n"
                +"⚠️ This is a private balance-presence check, not a buy/sell signal. "
                +"No real order, transfer, or withdrawal is enabled.";
            long id=sendToPrivateChat(msg);
            jdbc.update("""
                MERGE INTO binance_account_digest
                  (report_date,status,provider_message_id,sent_at_ms)
                KEY (report_date) VALUES (?, 'SENT', ?, ?)
                """,date,id,System.currentTimeMillis());
            log.info("binance_account_daily_digest_sent date={}",date);
        } catch(RuntimeException e) {
            // Never log HTTP URI containing token, response body, account info or
            // the exception message, which can unintentionally contain secrets.
            log.warn("binance_account_daily_digest_failed category={}",
                e.getClass().getSimpleName());
        }
    }

    private long sendToPrivateChat(String text) {
        try {
            String body=mapper.writeValueAsString(Map.of(
                "chat_id",privateChatId,"text",text,"disable_web_page_preview",true));
            var req=HttpRequest.newBuilder(
                URI.create("https://api.telegram.org/bot"+botToken+"/sendMessage"))
                .header("Content-Type","application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response=http.send(req,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("Telegram private digest HTTP rejected");
            var result=mapper.readTree(response.body());
            long id=result.path("result").path("message_id").asLong(-1);
            if(!result.path("ok").asBoolean(false) || id<=0) {
                throw new IllegalStateException("Telegram private digest missing ACK");
            }
            return id;
        } catch(java.io.IOException e) {
            throw new IllegalStateException("Telegram private digest network failure");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Telegram private digest interrupted");
        }
    }

    private static String yesNo(boolean value) {return value?"YES":"NO";}
}
