package nxt.peer;

import nxt.Block;
import nxt.Nxt;
import nxt.util.Logger;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

final class GetCumulativeDifficulty extends PeerServlet.PeerRequestHandler {

    static final GetCumulativeDifficulty instance = new GetCumulativeDifficulty();

    private GetCumulativeDifficulty() {}


    @Override
    JSONStreamAware processRequest(JSONObject request, Peer peer) {
        Logger.logMessage("获取累积难度GetCumulativeDifficulty");
        JSONObject response = new JSONObject();

        Block lastBlock = Nxt.getBlockchain().getLastBlock();
        Logger.logMessage("获取累积难度------获取最后一个区块");
        Logger.logMessage("获取到的累积难度为="+lastBlock.getCumulativeDifficulty().toString());
        Logger.logMessage("获取区块的高度为="+lastBlock.getHeight());
        response.put("cumulativeDifficulty", lastBlock.getCumulativeDifficulty().toString());
        response.put("blockchainHeight", lastBlock.getHeight());
        return response;
    }

}
