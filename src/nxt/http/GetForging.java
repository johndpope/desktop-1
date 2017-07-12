package nxt.http;

import nxt.Account;
import nxt.Generator;
import nxt.Nxt;
import nxt.crypto.Crypto;
import nxt.util.Convert;
import nxt.util.Logger;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

import static nxt.http.JSONResponses.*;


public final class GetForging extends APIServlet.APIRequestHandler {

    static final GetForging instance = new GetForging();

    private GetForging() {
        super("secretPhrase");
        Logger.logMessage("GetForging 创建GetForging的对象.......");
    }

    @Override
    JSONStreamAware processRequest(HttpServletRequest req) {

        String secretPhrase = req.getParameter("secretPhrase");
        if (secretPhrase == null) {
            Logger.logMessage("GetForging 未获取到私钥.....");
            return MISSING_SECRET_PHRASE;
        }
        Account account = Account.getAccount(Crypto.getPublicKey(secretPhrase));
        if (account == null) {
            Logger.logMessage("GetForging 账号是未知的......");
            return UNKNOWN_ACCOUNT;
        }

        Generator generator = Generator.getGenerator(secretPhrase);
        if (generator == null) {
            Logger.logMessage("GetForging 根据私钥获取到的generator对象为空，不能创建锻造的对象............");
            return NOT_FORGING;
        }

        JSONObject response = new JSONObject();
        long deadline = generator.getDeadline();
        Logger.logMessage("GetForging  锻造的截止时间为...="+deadline);
        response.put("deadline", deadline);
        int elapsedTime = Convert.getEpochTime() - Nxt.getBlockchain().getLastBlock().getTimestamp();
        Logger.logMessage("GetForging 锻造的剩余时间时间为"+elapsedTime);
        response.put("remaining", Math.max(deadline - elapsedTime, 0));
        return response;

    }

    @Override
    boolean requirePost() {
        return true;
    }

}
