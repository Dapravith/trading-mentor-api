package kh.mentor.trading.alerts;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Direct Bot API for explicit delivery ACK. Hermes remains the research assistant;
 * stdout-based Hermes cron delivery cannot acknowledge a message to our database.
 * No Telegram response body or token should be logged.
 */
@Component
public class TelegramBotTransport implements TelegramTransport {
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String token;
    private final String chatId;

    public TelegramBotTransport(JsonMapper json,
        @Value("${trading.alert.telegram-token:}") String token,
        @Value("${trading.alert.telegram-chat-id:}") String chatId,
        @Value("${trading.alert.delivery-enabled:false}") boolean enabled) {
        if (enabled && (token.isBlank() || chatId.isBlank())) {
            throw new IllegalStateException("Telegram delivery enabled without configured credentials");
        }
        this.json = json;
        this.token = token;
        this.chatId = chatId;
    }

    @Override
    public DeliveryReceipt send(String payload) {
        if (token.isBlank() || chatId.isBlank()) {
            throw new TelegramDeliveryException("NOT_CONFIGURED", true);
        }
        try {
            // Use POST JSON, never a URL query string containing message text.
            String body = json.writeValueAsString(Map.of(
                "chat_id", chatId, "text", payload, "disable_web_page_preview", true));
            HttpRequest request = HttpRequest.newBuilder(
                URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 200) {
                boolean permanent = status == 400 || status == 401 || status == 403 || status == 404;
                throw new TelegramDeliveryException(status == 429 ? "RATE_LIMITED" :
                    status >= 500 ? "TELEGRAM_UNAVAILABLE" :
                    permanent ? "TELEGRAM_REJECTED" : "HTTP_ERROR", permanent);
            }
            JsonNode node = json.readTree(response.body());
            long id = node.path("result").path("message_id").asLong(-1);
            if (!node.path("ok").asBoolean(false) || id <= 0) {
                throw new TelegramDeliveryException("BAD_ACK", false);
            }
            return new DeliveryReceipt(id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TelegramDeliveryException("INTERRUPTED", false);
        } catch (IOException e) {
            throw new TelegramDeliveryException("NETWORK_ERROR", false);
        } catch (TelegramDeliveryException e) {
            throw e;
        } catch (RuntimeException e) {
            // Do not propagate exception text; a URL may contain the bot token.
            throw new TelegramDeliveryException("PROVIDER_ERROR", false);
        }
    }
}
