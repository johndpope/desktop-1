package nxt.http;

import nxt.Block;
import nxt.Nxt;
import nxt.util.Convert;
import nxt.util.Logger;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

import static nxt.http.JSONResponses.*;

public final class GetBlock extends APIServlet.APIRequestHandler {

    static final GetBlock instance = new GetBlock();

    private GetBlock() {
        super("block");
    }

    @Override
    JSONStreamAware processRequest(HttpServletRequest req) {

        String block = req.getParameter("block");
        if (block == null) {
            return MISSING_BLOCK;
        }

        Block blockData;
        try {
            blockData = Nxt.getBlockchain().getBlock(Convert.parseUnsignedLong(block));
            if (blockData == null) {
                Logger.logMessage("GetBlock 区块不存在......");
                return UNKNOWN_BLOCK;
            }
        } catch (RuntimeException e) {
            return INCORRECT_BLOCK;
        }
        Logger.logMessage("GetBlock 获取到的区块的时间戳为 "+blockData.getTimestamp());

        return JSONData.block(blockData);

    }

}