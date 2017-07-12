package nxt.peer;

import nxt.Nxt;
import nxt.util.Convert;
import nxt.util.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.util.List;

final class GetNextBlockIds extends PeerServlet.PeerRequestHandler {

    static final GetNextBlockIds instance = new GetNextBlockIds();

    private GetNextBlockIds() {
        Logger.logMessage("GetNextBlockIds 获取下一个区块的ID");
    }


    @Override
    JSONStreamAware processRequest(JSONObject request, Peer peer) {
Logger.logMessage("GetNextBlockIds 根据请求和peer获取");
        JSONObject response = new JSONObject();

        JSONArray nextBlockIds = new JSONArray();
        Long blockId = Convert.parseUnsignedLong((String) request.get("blockId"));
        Logger.logMessage("获取的block ID为="+blockId);
        Logger.logMessage("下面可能要修改1440");
        List<Long> ids = Nxt.getBlockchain().getBlockIdsAfter(blockId, 1440);
        Logger.logMessage("获取到的ID的个数为="+ids.size());
        for (Long id : ids) {
            nextBlockIds.add(Convert.toUnsignedLong(id));
        }
        Logger.logMessage("返回下一块的ID的列表------");
        response.put("nextBlockIds", nextBlockIds);

        return response;
    }

}
