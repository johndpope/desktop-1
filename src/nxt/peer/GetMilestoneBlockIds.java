package nxt.peer;

import nxt.Block;
import nxt.Nxt;
import nxt.util.Convert;
import nxt.util.Logger;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

final class GetMilestoneBlockIds extends PeerServlet.PeerRequestHandler {

    static final GetMilestoneBlockIds instance = new GetMilestoneBlockIds();

    private GetMilestoneBlockIds() {}


    @Override
    JSONStreamAware processRequest(JSONObject request, Peer peer) {
    Logger.logMessage("GetMilestoneBlockIds 以下修改了1440的逻辑");
        JSONObject response = new JSONObject();
        try {
            long blockId;
            int height;
            int jump;
            int limit;

            JSONArray milestoneBlockIds = new JSONArray();
            /*从request中获取lastBlockId的值*/
            String lastBlockIdString = (String) request.get("lastBlockId");
            Logger.logMessage("获取到的请求中lastBlockIdString="+lastBlockIdString);
            /*第一次请求时lastBlockID的值不为空*/
            /*当请求中lastBlockIdString的值不为空值进行的操*/
            if (lastBlockIdString != null) {
                Logger.logMessage("当请求中lastBlockIdString的值不为空值进行的操作.......");

                Long lastBlockId = Convert.parseUnsignedLong(lastBlockIdString);
                /*获取当前节点的最后有一个区块的高度*/
                Long myLastBlockId = Nxt.getBlockchain().getLastBlock().getId();

                Logger.logMessage("以GetMilestoneBlockIds 下可能有问题......");
                /*若当前结点的区块的最后一个块的ID等于请求中的lastBlockId，且链中存在id为lastBlockId的区块*/
                if (myLastBlockId.equals(lastBlockId) || Nxt.getBlockchain().hasBlock(lastBlockId)) {
                   /*向 milestoneBlockIds中添加lastBlockIdString*/
                    milestoneBlockIds.add(lastBlockIdString);

                    response.put("milestoneBlockIds", milestoneBlockIds);
                    if (myLastBlockId.equals(lastBlockId)) {
                        response.put("last", Boolean.TRUE);
                    }
                    /*返回个请求者*/
                    return response;
                }
            }
/*以下盲点*/
            /*获取请求中的lastMilestoneBlockId*/
            String lastMilestoneBlockIdString = (String) request.get("lastMilestoneBlockId");
            if (lastMilestoneBlockIdString != null) {
                /*从当前节点中获取最后一个lastMilestoneBlock*/
                Block lastMilestoneBlock = Nxt.getBlockchain().getBlock(Convert.parseUnsignedLong(lastMilestoneBlockIdString));
                if (lastMilestoneBlock == null) {
                    Logger.logMessage("不存在区块="+lastMilestoneBlock);
                    throw new IllegalStateException("Don't have block " + lastMilestoneBlockIdString);
                }
                /*获取最后一个lastMilestoneBlock的高度*/
                height = lastMilestoneBlock.getHeight();
                //jump = Math.min(1440, Nxt.getBlockchain().getLastBlock().getHeight() - height);
                /*设置不能超过1440，因为一个区块在1440块生成后不能变*/
                jump = Math.min(1440, Nxt.getBlockchain().getLastBlock().getHeight() - height);

                height = Math.max(height - jump, 0);

                limit = 10;
            } else if (lastBlockIdString != null) {
                height = Nxt.getBlockchain().getLastBlock().getHeight();
                jump = 10;
                limit = 10;
            } else {
                peer.blacklist();
                response.put("error", "Old getMilestoneBlockIds protocol not supported, please upgrade");
                return response;
            }
            blockId = Nxt.getBlockchain().getBlockIdAtHeight(height);

            while (height > 0 && limit-- > 0) {
                milestoneBlockIds.add(Convert.toUnsignedLong(blockId));
                blockId = Nxt.getBlockchain().getBlockIdAtHeight(height);
                height = height - jump;
            }
            Logger.logMessage("response中的milestoneBlockIds为 ="+milestoneBlockIds);
            response.put("milestoneBlockIds", milestoneBlockIds);

        } catch (RuntimeException e) {
            Logger.logMessage("出现了异常...... GetMilestoneBlockIds ");
            Logger.logDebugMessage(e.toString());
            response.put("error", e.toString());
        }

        return response;
    }

}
