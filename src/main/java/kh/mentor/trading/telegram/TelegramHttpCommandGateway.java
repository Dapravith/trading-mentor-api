package kh.mentor.trading.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import kh.mentor.trading.alerts.TelegramDeliveryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bot API long polling (getUpdates). Polling needs no public webhook, so the
 * app can stay bound to 127.0.0.1. Never log the token, URL or response body.
 */
@Component
public class TelegramHttpCommandGateway implements TelegramCommandGateway {
    private static final int MAX_TEXT = 4000; // Telegram hard limit is 4096
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String token;

    public TelegramHttpCommandGateway(JsonMapper json,
        @Value("${trading.alert.telegram-token:}") String token) {
        this.json = json;
        this.token = token;
    }

    @Override
    public List<IncomingMessage> poll(long offset, int timeoutSeconds) {
        JsonNode result = call("getUpdates", Map.of("offset", offset,
            "timeout", timeoutSeconds, "allowed_updates", List.of("message")),
            Duration.ofSeconds(timeoutSeconds + 10L));
        List<IncomingMessage> messages = new ArrayList<>();
        for (JsonNode update : result) {
            JsonNode message = update.path("message");
            JsonNode from = message.path("from");
            messages.add(new IncomingMessage(update.path("update_id").asLong(),
                message.path("chat").path("id").asLong(0),
                message.path("chat").path("type").asString(""),
                from.isMissingNode() ? null : from.path("id").asLong(),
                message.path("text").asString(""),
                message.path("date").asLong(0)));
        }
        return messages;
    }

    @Override
    public void reply(long chatId, String text) {
        String body = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT - 1) + "…" : text;
        call("sendMessage", Map.of("chat_id", chatId, "text", body,
            "disable_web_page_preview", true), Duration.ofSeconds(12));
    }

    @Override
    public void sendTyping(long chatId) {
        call("sendChatAction", Map.of("chat_id", chatId, "action", "typing"),
            Duration.ofSeconds(5));
    }

    @Override
    public void registerCommands(Map<String, String> commands) {
        List<Map<String, String>> list = commands.entrySet().stream()
            .map(e -> Map.of("command", e.getKey(), "description", e.getValue())).toList();
        call("setMyCommands", Map.of("commands", list), Duration.ofSeconds(12));
    }

    private JsonNode call(String method, Map<String, Object> payload, Duration timeout) {
        if (token.isBlank()) {
            throw new TelegramDeliveryException("NOT_CONFIGURED", true);
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://api.telegram.org/bot" + token + "/" + method))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload)))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 200) {
                // 409: another getUpdates poller or a webhook already owns this bot.
                throw toTelegramException(status, retryAfterSeconds(response.body()));
            }
            JsonNode node = json.readTree(response.body());
            if (!node.path("ok").asBoolean(false)) {
                throw new TelegramDeliveryException("BAD_ACK", false);
            }
            return node.path("result");
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

    private TelegramDeliveryException toTelegramException(int status, long retryAfterSeconds) {
        String code;
        boolean isPermanent = false;

        if (status == 409) {
            code = "POLLING_CONFLICT";
        } else if (status == 429) {
            code = "RATE_LIMITED";
        } else if (status >= 500) {
            code = "TELEGRAM_UNAVAILABLE";
        } else if (status == 400 || status == 401 || status == 403 || status == 404) {
            // Bad request, bad token, bot blocked by the user, or unknown method: retrying won't help.
            code = "TELEGRAM_REJECTED";
            isPermanent = true;
        } else {
            code = "HTTP_ERROR";
        }
        return new TelegramDeliveryException(code, isPermanent, retryAfterSeconds);
    }

    /** Reads only the numeric flood-control hint; the body itself is never logged. */
    private long retryAfterSeconds(String body) {
        try {
            return json.readTree(body).path("parameters").path("retry_after").asLong(0);
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
