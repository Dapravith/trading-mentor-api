package kh.mentor.trading.account;

import static org.junit.jupiter.api.Assertions.*;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

class BinanceSpotAccountReaderTest {
    private final JsonMapper mapper=new JsonMapper();

    private JsonNode parse(String json) throws Exception {return mapper.readTree(json);}

    private JsonNode allowedPermissions() throws Exception {
        return parse("""
            {
              "enableReading":true,
              "enableWithdrawals":false,
              "enableSpotAndMarginTrading":false,
              "enableFutures":false,
              "enableMargin":false,
              "enableInternalTransfer":false,
              "permitsUniversalTransfer":false,
              "enableFixApiTrade":false,
              "enablePortfolioMarginTrading":false,
              "enableVanillaOptions":false
            }
            """);
    }
    private JsonNode validAccount() throws Exception {
        return parse("""
            {"accountType":"SPOT","balances":[
              {"asset":"BTC","free":"0.002","locked":"0"},
              {"asset":"ETH","free":"0","locked":"0.04"},
              {"asset":"USDT","free":"0","locked":"0"},
              {"asset":"LTC","free":"0","locked":"0"}
            ]}
            """);
    }

    @Test void hmacSha256MatchesDeterministicVector() {
        assertEquals("e80444d3300edcb80b05d266439eb51c0f9551b00a09836c26b05dea9af0eba3",
            BinanceSpotAccountReader.hmacSha256("test-secret",
                "recvWindow=5000&timestamp=1700000000000"));
    }

    @Test void readsOnlyApprovedEndpointsAndMasksQuantities() throws Exception {
        var permissions=allowedPermissions();
        var account=validAccount();
        var reader=new BinanceSpotAccountReader((path,parameters)-> {
            if(path.equals("/sapi/v1/account/apiRestrictions")) {
                assertEquals("",parameters);
                return permissions;
            }
            assertEquals("/api/v3/account",path);
            assertEquals("omitZeroBalances=true",parameters);
            return account;
        });
        var result=reader.check();
        assertEquals(2,result.nonZeroAssetCount());
        assertTrue(result.readOnly());
        assertTrue(result.holdsBtc());
        assertTrue(result.holdsEth());
        assertFalse(result.holdsUsdt());
        assertFalse(result.toString().contains("0.002"));
        assertFalse(result.toString().contains("0.04"));
        assertFalse(result.toString().contains("uid"));
    }

    @Test void rejectsTradeAndWithdrawalPermissionsBeforeReadingBalances() throws Exception {
        for(String flag:new String[]{
            "enableWithdrawals","enableSpotAndMarginTrading",
            "enableInternalTransfer","permitsUniversalTransfer","enableFutures",
            "enableMargin","enableFixApiTrade",
            "enablePortfolioMarginTrading","enableVanillaOptions"
        }) {
            String original=allowedPermissions().toString();
            // Replace each explicit false with true once; simulate an over-privileged key.
            var unauthorized=parse(original.replace("\""+flag+"\":false",
                "\""+flag+"\":true"));
            final int[] calls={0};
            var reader=new BinanceSpotAccountReader((path,parameters)->{
                calls[0]++;
                if(calls[0]>1)fail("Account balances must NOT be queried after unsafe key detected");
                return unauthorized;
            });
            assertThrows(IllegalStateException.class,reader::check,flag);
            assertEquals(1,calls[0]);
        }
    }

    @Test void rejectsMissingReadingPermissionOrMissingSensitivePermissionFields() throws Exception {
        var reader=new BinanceSpotAccountReader((path,parameters)-> {
            try{return parse("{\"enableReading\":true,\"enableWithdrawals\":false}");}
            catch(Exception e){throw new AssertionError(e);}
        });
        assertThrows(IllegalStateException.class,reader::check);
        var reader2=new BinanceSpotAccountReader((path,parameters)->{
            try{return parse("{\"enableReading\":false,\"enableWithdrawals\":false,\"enableSpotAndMarginTrading\":false}");}
            catch(Exception e){throw new AssertionError(e);}
        });
        assertThrows(IllegalStateException.class,reader2::check);
    }

    @Test void missingOrNonBooleanTradingPermissionMustFailClosed() throws Exception {
        var good=allowedPermissions();
        for (String flag:new String[] {
            "enableWithdrawals","enableSpotAndMarginTrading","enableInternalTransfer",
            "enableFixApiTrade","enableVanillaOptions","enablePortfolioMarginTrading"
        }) {
            String json=good.toString().replace("\""+flag+"\":false", "\""+flag+"\":null");
            var response=parse(json);
            var reader=new BinanceSpotAccountReader((path,args)->response);
            assertThrows(IllegalStateException.class,reader::check,flag);
        }
        var reader=new BinanceSpotAccountReader((path,args)->{
            try {return parse("{\"enableReading\":true}");}
            catch(Exception e) {throw new AssertionError(e);}
        });
        assertThrows(IllegalStateException.class,reader::check);
    }

    @Test void rejectsBadBalancesFailClosed() throws Exception {
        var perms=allowedPermissions();
        for(String bad:new String[]{
            "{\"balances\":[{\"asset\":\"BTC\",\"free\":\"bad\",\"locked\":\"0\"}]}",
            "{\"balances\":[{\"asset\":\"BTC\",\"free\":\"-1\",\"locked\":\"0\"}]}",
            "{\"balances\":[{\"asset\":\"BTC/USDT\",\"free\":\"1\",\"locked\":\"0\"}]}",
            "{\"noBalances\":[]}"
        }) {
            var data=parse(bad);
            var reader=new BinanceSpotAccountReader((path,args)->
                path.contains("apiRestrictions")?perms:data);
            assertThrows(IllegalStateException.class,reader::check);
        }
    }
}
