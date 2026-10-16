package kh.mentor.trading.account;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HMAC read-only account connector. No order/transfer methods exist.
 * Validates Binance's key permission report before reading any balances.
 * Raw asset amounts and account identifiers are NEVER emitted in the response.
 */
@Component
@ConditionalOnProperty(prefix="trading.account",name="enabled",havingValue="true")
public final class BinanceSpotAccountReader {
    @FunctionalInterface
    interface SignedReader {
        JsonNode get(String path,String parameters);
    }

    private final SignedReader reader;

    public BinanceSpotAccountReader(JsonMapper mapper,
        @Value("${trading.account.api-key:}") String apiKey,
        @Value("${trading.account.api-secret:}") String apiSecret) {
        if(apiKey.isBlank() || apiSecret.isBlank()) {
            throw new IllegalStateException("Read-only Binance API credentials are missing");
        }
        HttpClient http=HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
        this.reader=(path,params)->request(http,mapper,apiKey,apiSecret,path,params);
    }

    /** Package-private deterministic test constructor; no network. */
    BinanceSpotAccountReader(SignedReader reader) { this.reader=reader; }

    public AccountStatus check() {
        JsonNode permissions=reader.get("/sapi/v1/account/apiRestrictions","");
        // Do not trust an account endpoint's canTrade flag: it describes account capability,
        // NOT the API key's actual permissions.
        if(!permissions.path("enableReading").asBoolean(false)) {
            throw new IllegalStateException("API key does not permit read-only access");
        }
        // Incomplete API-permission JSON must fail closed. Previously missing
        // trade/transfer flags defaulted to false, which was unsafe.
        for (String flag : new String[] {
                "enableWithdrawals", "enableSpotAndMarginTrading", "enableFutures",
                "enableMargin", "enableFixApiTrade", "enableInternalTransfer",
                "permitsUniversalTransfer", "enablePortfolioMarginTrading",
                "enableVanillaOptions" }) {
            JsonNode value=permissions.get(flag);
            if(value==null || !value.isBoolean() || value.asBoolean()) {
                throw new IllegalStateException("Unverified or elevated Binance API key scope");
            }
        }
        JsonNode account=reader.get("/api/v3/account","omitZeroBalances=true");
        if(!account.path("balances").isArray()) {
            throw new IllegalStateException("Unexpected Spot account payload");
        }
        Set<String> assets=new HashSet<>();
        for(JsonNode balance:account.path("balances")) {
            String asset=balance.path("asset").asText("");
            if(!asset.matches("[A-Z0-9]{2,20}")) {
                throw new IllegalStateException("Malformed asset identifier");
            }
            try {
                BigDecimal free=new BigDecimal(balance.path("free").asText(""));
                BigDecimal locked=new BigDecimal(balance.path("locked").asText(""));
                if(free.signum()<0 || locked.signum()<0) {
                    throw new IllegalStateException("Invalid negative asset amount");
                }
                if(free.add(locked).signum()>0) assets.add(asset);
            } catch(NumberFormatException bad) {
                throw new IllegalStateException("Invalid numeric Spot balance");
            }
        }
        return new AccountStatus(Instant.now().toString(),true,assets.size(),
            assets.contains("BTC"),assets.contains("ETH"),assets.contains("USDT"),
            "SPOT_READ_ONLY","No price conversion, BUY/SELL decision, or orders executed");
    }

    public record AccountStatus(String checkedAtUtc,boolean readOnly,int nonZeroAssetCount,
        boolean holdsBtc,boolean holdsEth,boolean holdsUsdt,String mode,String disclaimer) {}

    public static String hmacSha256(String secret,String data) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC signing is unavailable");
        }
    }

    private static JsonNode request(HttpClient http,JsonMapper mapper,String apiKey,
        String apiSecret,String path,String parameters) {
        if(!path.equals("/sapi/v1/account/apiRestrictions")
            && !path.equals("/api/v3/account")) {
            throw new IllegalArgumentException("Only the two approved read-only Binance routes are allowed");
        }
        if(!parameters.isEmpty() && !parameters.equals("omitZeroBalances=true")) {
            throw new IllegalArgumentException("Unsupported account query arguments");
        }
        String signing=(parameters.isEmpty()?"":parameters+"&")
            +"recvWindow=5000&timestamp="+System.currentTimeMillis();
        String signature=hmacSha256(apiSecret,signing);
        URI uri=URI.create("https://api.binance.com"+path+"?"+signing+"&signature="+signature);
        HttpRequest request=HttpRequest.newBuilder(uri)
            .GET().timeout(Duration.ofSeconds(12))
            .header("X-MBX-APIKEY",apiKey)
            .header("Accept","application/json").build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200) {
                // Do not log the request URL, response body, or credentials.
                throw new IllegalStateException(
                    "Binance account request rejected (HTTP "+response.statusCode()+")");
            }
            return mapper.readTree(response.body());
        } catch(java.io.IOException ex) {
            throw new IllegalStateException("Binance account API connectivity failure");
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Binance account API request interrupted");
        }
    }
}
