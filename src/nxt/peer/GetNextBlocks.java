package nxt.peer;

import nxt.Block;
import nxt.Constants;
import nxt.Nxt;
import nxt.util.Convert;
import nxt.util.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.util.ArrayList;
import java.util.List;

final class GetNextBlocks extends PeerServlet.PeerRequestHandler {

    static final GetNextBlocks instance = new GetNextBlocks();

    private GetNextBlocks() {

        Logger.logMessage("GetNextBlocks 获取下一个区块");
    }


    @Override
    JSONStreamAware processRequest(JSONObject request, Peer peer) {

        JSONObject response = new JSONObject();

        List<Block> nextBlocks = new ArrayList<>();
        int totalLength = 0;
        Long blockId = Convert.parseUnsignedLong((String) request.get("blockId"));
        Logger.logMessage("请求中的ID为="+blockId);
        Logger.logMessage("以下可能需要修改+1440");
        /*获取指定区块ID后的1440个区块*/
        List<? extends Block> blocks = Nxt.getBlockchain().getBlocksAfter(blockId, 1440);

        Logger.logMessage("获取到的块的个数为="+blocks.size());
        for (Block block : blocks) {
            int length = Constants.BLOCK_HEADER_LENGTH + block.getPayloadLength();
            if (totalLength + length > 1048576) {
                Logger.logMessage("此处可能出问题......");
                break;
            }
            nextBlocks.add(block);
            totalLength += length;
        }

        JSONArray nextBlocksArray = new JSONArray();
        for (Block nextBlock : nextBlocks) {
            nextBlocksArray.add(nextBlock.getJSONObject());
        }
        Logger.logMessage("将获取到区块以数组的形式返回..........");
        response.put("nextBlocks", nextBlocksArray);

        return response;
    }

}
