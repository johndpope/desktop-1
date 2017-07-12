package nxt;

import org.json.simple.JSONObject;

import java.math.BigInteger;
import java.util.List;

public interface Block {

    int getVersion();/*获取版本号*/

    Long getId();/*获取ID号*/

    String getStringId();

    int getHeight();/*获取区块的高度*/

    int getTimestamp();/*获取时间戳*/

    Long getGeneratorId();/*获取记账者ID*/

    byte[] getGeneratorPublicKey();/*获取记账者公钥*/

    Long getPreviousBlockId();/*获取上一个块的Id*/

    byte[] getPreviousBlockHash();/*获取上一个块的hash*/

    Long getNextBlockId();/*获取下一块的ID*/

    long getTotalAmountNQT();/*获取总的NQT的数量*/

    long getTotalFeeNQT();/*获取总的费用*/

    int getPayloadLength();

    byte[] getPayloadHash();/*获取支付的载荷*/

    List<Long> getTransactionIds();/*获取块中所有交易的ID*/

    List<? extends Transaction> getTransactions();/*获取块中所有的交易*/

    byte[] getGenerationSignature();/*获取记账者签名*/

    byte[] getBlockSignature();/*获取块签名*/

    long getBaseTarget();/*获取基准目标*/

    BigInteger getCumulativeDifficulty();/*获取难度系数*/

    JSONObject getJSONObject();/*将对象封装为json对象，估调用方便*/

}
