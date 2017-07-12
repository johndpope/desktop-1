package nxt;

import nxt.crypto.Crypto;
import nxt.peer.Peer;
import nxt.peer.Peers;
import nxt.util.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

final class BlockchainProcessorImpl implements BlockchainProcessor {
 private static final byte[] CHECKSUM_TRANSPARENT_FORGING = new byte[]{ -96,52,49,-77,91,-92,60,101,73,44,105,33,0,-57,-23,-53,-53,-79,-25,108,94,-11,-111,1,73,16,-114,115,107,103,-62,-13};

    private static final byte[] CHECKSUM_NQT_BLOCK = Constants.isTestnet ? new byte[]{-126, -117, -94, -16, 125, -94, 38, 10, 11, 37, -33, 4, -70, -8, -40, -80, 18, -21, -54, -126, 109, -73, 63, -56, 67, 59, -30, 83, -6, -91, -24, 34}
            : new byte[]{-96,52,49,-77,91,-92,60,101,73,44,105,33,0,-57,-23,-53,-53,-79,-25,108,94,-11,-111,1,73,16,-114,115,107,103,-62,-13};


    private static final BlockchainProcessorImpl instance = new BlockchainProcessorImpl();

    static BlockchainProcessorImpl getInstance() {
        Logger.logMessage("BlockchainProcessorImpl 创建区块链处理器的对象......");
        return instance;
    }

    private final BlockchainImpl blockchain = BlockchainImpl.getInstance();
    private final TransactionProcessorImpl transactionProcessor = TransactionProcessorImpl.getInstance();
    /*区块监听器*/
    private final Listeners<Block, Event> blockListeners = new Listeners<>();
    private volatile Peer lastBlockchainFeeder;/*网络难度系数最大的一个块的分支peer*/
    private volatile int lastBlockchainFeederHeight;/*网络中最后一块的分支高度*/

    private volatile boolean isScanning;

    private final Runnable getMoreBlocksThread = new Runnable() {

        private final JSONStreamAware getCumulativeDifficultyRequest;

        {
          //  Logger.logMessage("启动线程获取更多的区块");
            JSONObject request = new JSONObject();
            request.put("requestType", "getCumulativeDifficulty");
            getCumulativeDifficultyRequest = JSON.prepareRequest(request);
        }

        private boolean peerHasMore;

        @Override
        public void run() {
          //  Logger.logMessage("我是一个新的线程=========开始执线程中代码======================\n");
              //  Logger.logMessage("获取更多区块的线程+getMoreBlocksThread "+peerHasMore);
            try {
                try {
                    peerHasMore = true;
               //     Logger.logMessage("获取任意一个在线的节点并请求连接");
                    Peer peer = Peers.getAnyPeer(Peer.State.CONNECTED, true);
                    if (peer == null) {
                        Logger.logMessage("peer 不存在");
                        return;
                    }
               //     Logger.logMessage("最后一个区块创建的peer"+peer);
                    lastBlockchainFeeder = peer; //网络中权重好的
                    JSONObject response = peer.send(getCumulativeDifficultyRequest);
                    if (response == null) {
                        Logger.logMessage("响应为null");
                        return;
                    }
                  //  Logger.logMessage("获取最后一个块的难度系数...curCumulativeDifficulty");
                    BigInteger curCumulativeDifficulty = blockchain.getLastBlock().getCumulativeDifficulty();

                    Logger.logMessage("最后一个区块的难度系数为"+curCumulativeDifficulty);

                    String peerCumulativeDifficulty = (String) response.get("cumulativeDifficulty");
                    Logger.logMessage("获取peer其他节点的最后一个块的难度系数值");
                    Logger.logMessage("peer的难度系数为"+peerCumulativeDifficulty);
                    if (peerCumulativeDifficulty == null) {
                        Logger.logMessage("peer难度系数为空");
                        return;
                    }

                    BigInteger betterCumulativeDifficulty = new BigInteger(peerCumulativeDifficulty);
                    Logger.logMessage(" 比较好的难度系数值为"+ betterCumulativeDifficulty);
                    if (betterCumulativeDifficulty.compareTo(curCumulativeDifficulty) <= 0) {
                        Logger.logMessage("比较好的难度系数值小于当前的的难度系数值");
                        return;
                    }/**如果获取到了区块链的高度，设置最后一个分叉的区块高度*/
                    if (response.get("blockchainHeight") != null) {

                        lastBlockchainFeederHeight = ((Long) response.get("blockchainHeight")).intValue();

                        Logger.logMessage("最后一个区块的高度"+lastBlockchainFeederHeight);
                    }
                    /**设置公共的区块ID*/
                    Long commonBlockId = Genesis.GENESIS_BLOCK_ID;
                    Logger.logMessage("114--公共块的ID "+commonBlockId);
                    /**如果当时区块的高度不等于创世块区块高度，则开始设置公共的里程碑的ID*/
                    if (! blockchain.getLastBlock().getId().equals(Genesis.GENESIS_BLOCK_ID)) {
                        Logger.logMessage("公共的ID不等于创世块的ID，重新设置公共的ID");
                        commonBlockId = getCommonMilestoneBlockId(peer);
                    }
                    /**如果公共的ID为空不存更多的peer*/
                    if (commonBlockId == null || !peerHasMore) {
                        Logger.logMessage("公共的ID为null，且不存在更多的peer.......");
                        return;
                    }
                    /**获取公共的区块*/
                    commonBlockId = getCommonBlockId(peer, commonBlockId);
                    Logger.logMessage("p2p中公共拥有的块ID"+commonBlockId);
                    /*从P2p*/
                    if (commonBlockId == null || !peerHasMore) {
                        return;
                    }
                    /**从当前数据库中查找出公共的块*/
                    final Block commonBlock = BlockDb.findBlock(commonBlockId);
                    if (blockchain.getLastBlock().getHeight() - commonBlock.getHeight() >= 720) {
                        Logger.logMessage("块之间的高度相差大于720");
                        return;
                    }

                    Long currentBlockId = commonBlockId;
                    /*创建一个列表用来保存分支的区块*/
                    List<BlockImpl> forkBlocks = new ArrayList<>();

                    outer:
                    while (true) {

                        JSONArray nextBlocks = getNextBlocks(peer, currentBlockId);
                        if (nextBlocks == null || nextBlocks.size() == 0) {
                            Logger.logMessage("已经不存在下一个块"+nextBlocks );
                            break;
                        }

                        synchronized (blockchain) {

                            for (Object o : nextBlocks) {
                                JSONObject blockData = (JSONObject) o;
                                BlockImpl block;
                                try {
                                    block = parseBlock(blockData);
                                } catch (NxtException.ValidationException e) {
                                    Logger.logMessage("Cannot validate block: " + e.toString()
                                            + ", will try again later", e);
                                    break outer;
                                } catch (RuntimeException e) {
                                    Logger.logDebugMessage("Failed to parse block: " + e.toString(), e);
                                    peer.blacklist();
                                    return;
                                }
                                currentBlockId = block.getId();
                                /*若获取的区块的的前一个id等于当前最后一个节点的区块ID,否则进行分支处理操作*/
                                if (blockchain.getLastBlock().getId().equals(block.getPreviousBlockId())) {
                                    try {
                                        pushBlock(block);
                                    } catch (BlockNotAcceptedException e) {
                                        peer.blacklist(e);
                                        return;
                                    }
                                } else if (! BlockDb.hasBlock(block.getId())) {
                                    forkBlocks.add(block);
                                }

                            }

                        } //synchronized

                    }
                    /*存在分叉且高度小于720时就处理分叉的情况*/
                    if (! forkBlocks.isEmpty() && blockchain.getLastBlock().getHeight() - commonBlock.getHeight() < 720) {
                        processFork(peer, forkBlocks, commonBlock);
                    }

                } catch (Exception e) {
                    Logger.logDebugMessage("Error in milestone blocks processing thread", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

        /**
         * 根据peer获取公共里程碑的ID
         * @param peer
         * @return
         */
        private Long getCommonMilestoneBlockId(Peer peer) {
            /*设置lastMilestoneBlockId*/
            String lastMilestoneBlockId = null;

            while (true) {
                JSONObject milestoneBlockIdsRequest = new JSONObject();
                /*构造请求的类型*/
                milestoneBlockIdsRequest.put("requestType", "getMilestoneBlockIds");
                if (lastMilestoneBlockId == null) {
                    /*lastMilestoneBlockId 不存在在则lastMilestoneBlockId的*/
                    milestoneBlockIdsRequest.put("lastBlockId", blockchain.getLastBlock().getStringId());
                } else {
                    milestoneBlockIdsRequest.put("lastMilestoneBlockId", lastMilestoneBlockId);
                }
                /*向网络中发送json数据请求milestoneBlockIdsRequest，并获取返回*/
                JSONObject response = peer.send(JSON.prepareRequest(milestoneBlockIdsRequest));
                if (response == null) {
                    return null;
                }
                /*获取返回milestoneBlockIds的数组*/
                JSONArray milestoneBlockIds = (JSONArray) response.get("milestoneBlockIds");
                if (milestoneBlockIds == null) {
                    return null;
                }
                /*若milestoneBlockIds为空则设置为Genesis.GENESIS_BLOCK_ID*/
                if (milestoneBlockIds.isEmpty()) {
                    return Genesis.GENESIS_BLOCK_ID;
                }
                // prevent overloading with blockIds
                Logger.logMessage("从一个peer上收到20个里成块是，就将该节点加入黑名单");
                /*若收到的milestoneBlockIds.size大于20，则将该节点加入黑名单，表名该节点分支太多*/
                if (milestoneBlockIds.size() > 20) {
                    Logger.logDebugMessage("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many milestoneBlockIds, blacklisting");
                    peer.blacklist();
                    return null;
                }
                if (Boolean.TRUE.equals(response.get("last"))) {
                    peerHasMore = false;
                }
                /*开始处理milestoneBlockIds*/
                for (Object milestoneBlockId : milestoneBlockIds) {
                    Long blockId = Convert.parseUnsignedLong((String) milestoneBlockId);
                    /*若数据库中存在milestoneBlockId 则返回milestoneBlockId */
                    if (BlockDb.hasBlock(blockId)) {
                        /*若lastMilestoneBlockId为空且milestoneBlockIds.size() > 1 则将peerHasMore设置为false*/
                        if (lastMilestoneBlockId == null && milestoneBlockIds.size() > 1) {
                            peerHasMore = false;
                        }
                        return blockId;
                    }
                    lastMilestoneBlockId = (String) milestoneBlockId;
                }
            }

        }

        /**
         * 获取公共的快
         * @param peer
         * @param commonBlockId
         * @return
         */
        private Long getCommonBlockId(Peer peer, Long commonBlockId) {
        Logger.logMessage("执行getCommonBlockId获取公共的ID");
            while (true) {
                /*创建一request的JSON对象*/
                JSONObject request = new JSONObject();
                /*设置请求类型为getNextBlockID */
                request.put("requestType", "getNextBlockIds");
                /*设置获取的ID为 commonBlockId*/
                request.put("blockId", Convert.toUnsignedLong(commonBlockId));
                /*向peer发送并请求一个返回*/
                JSONObject response = peer.send(JSON.prepareRequest(request));

                Logger.logMessage("返回的对象为空........");
                if (response == null) {
                    Logger.logMessage("getCommonBlockId----->返回为null");
                    return null;
                }
                /*创建一个JSOANArray来保存response中返回的nextBlockIds*/
                JSONArray nextBlockIds = (JSONArray) response.get("nextBlockIds");
                if (nextBlockIds == null || nextBlockIds.size() == 0) {
                    Logger.logMessage("下一个块的ID为null");
                    return null;
                }
                // prevent overloading with blockIds
                if (nextBlockIds.size() > 1440) {
                    /*若块小于1440时表明peer有问题，将peer加入黑名单*/
                    Logger.logDebugMessage("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many nextBlockIds, blacklisting");
                    peer.blacklist();
                    return null;
                }
                    /*处理nextBlockIds数组*/
                for (Object nextBlockId : nextBlockIds) {
                    Long blockId = Convert.parseUnsignedLong((String) nextBlockId);
                    /*若数据库中不存在ID为nextBlockId的块，则返回nextBlockId*/
                    if (! BlockDb.hasBlock(blockId)) {

                        return commonBlockId;
                    }

                    commonBlockId = blockId;
                    Logger.logMessage("执行正常...."+commonBlockId);
                }
            }

        }

        private JSONArray getNextBlocks(Peer peer, Long curBlockId) {
            /*创建一个JSONObject来保存request*/
            JSONObject request = new JSONObject();
            /*设置请求的类型*/
            request.put("requestType", "getNextBlocks");
            /*设置blockId*/
            request.put("blockId", Convert.toUnsignedLong(curBlockId));
            JSONObject response = peer.send(JSON.prepareRequest(request));
            if (response == null) {
                return null;
            }
            /*创建一个列表来保存nextBlocks*/
            JSONArray nextBlocks = (JSONArray) response.get("nextBlocks");
            if (nextBlocks == null) {
                return null;
            }
            // prevent overloading with blocks
            /*若返回大于1440,则说明节点出了问题，将peer加入黑名单*/
            if (nextBlocks.size() > 1440) {
                Logger.logDebugMessage("Obsolete or rogue peer " + peer.getPeerAddress() + " sends too many nextBlocks, blacklisting");
                peer.blacklist();
                return null;
            }

            return nextBlocks;

        }

        private void processFork(Peer peer, final List<BlockImpl> forkBlocks, final Block commonBlock) {

            synchronized (blockchain) {
                //获取区块链的难度系数
                BigInteger curCumulativeDifficulty = blockchain.getLastBlock().getCumulativeDifficulty();
                boolean needsRescan;/*是否需要重新扫描*/

                try {
                    while (! blockchain.getLastBlock().getId().equals(commonBlock.getId()) && popLastBlock()) {
                   //如果当前区块的ID不等于公共的ID则直接弹出最后一个区块，直到blockchain.getLastBlock().getId().equals(commonBlock.getId()

                    }
                    /*区块链的最后一个块的ID等于公共的区的ID*/
                    if (blockchain.getLastBlock().getId().equals(commonBlock.getId())) {
                        for (BlockImpl block : forkBlocks) {
                            /*如果当前区块链的最后一区块的前一个区块的id等于当前从分支表中取出的块的ID，则将当前块加入到区块链区块链的表中*/
                            if (blockchain.getLastBlock().getId().equals(block.getPreviousBlockId())) {
                                try {
                                    pushBlock(block);
                                } catch (BlockNotAcceptedException e) {
                                    peer.blacklist(e);
                                    break;
                                }
                            }
                        }
                    }
/*如果区块链的最后一个块的难度系数小于当前的难度系数，则重新扫描*/
                    needsRescan = blockchain.getLastBlock().getCumulativeDifficulty().compareTo(curCumulativeDifficulty) < 0;
                    if (needsRescan) {
                        Logger.logDebugMessage("Rescan caused by peer " + peer.getPeerAddress() + ", blacklisting");
                        peer.blacklist();
                    }
                } catch (TransactionType.UndoNotSupportedException e) {
                    Logger.logDebugMessage(e.getMessage());
                    Logger.logDebugMessage("Popping off last block not possible, will do a rescan");
                    needsRescan = true;
                }

                if (needsRescan) {
                    if (commonBlock.getNextBlockId() != null) {
                        Logger.logDebugMessage("Last block is " + blockchain.getLastBlock().getStringId() + " at " + blockchain.getLastBlock().getHeight());
                        Logger.logDebugMessage("Deleting blocks after height " + commonBlock.getHeight());
                        //删除公共块ID后的所有块
                        BlockDb.deleteBlocksFrom(commonBlock.getNextBlockId());
                    }
                    Logger.logMessage("Will do a re-scan");
                    //激活一个时间重新扫描开始的事件
                    blockListeners.notify(commonBlock, Event.RESCAN_BEGIN);
                    scan();
                    //激活一个时间重新扫描结束
                    blockListeners.notify(commonBlock, Event.RESCAN_END);
                    Logger.logDebugMessage("Last block is " + blockchain.getLastBlock().getStringId() + " at " + blockchain.getLastBlock().getHeight());
                }
            } // synchronized

        }

    };

    private BlockchainProcessorImpl() {

        blockListeners.addListener(new Listener<Block>() {
            @Override
            public void notify(Block block) {
                if (block.getHeight() % 5000 == 0) {
                    Logger.logMessage("processed block " + block.getHeight());
                }
            }
        }, Event.BLOCK_SCANNED);

        ThreadPool.runBeforeStart(new Runnable() {
            @Override
            public void run() {

                System.out.println("程序在开始时执行.......");
                addGenesisBlock();
                scan();
            }
        });

        ThreadPool.scheduleThread(getMoreBlocksThread, 1);

    }

    @Override
    public boolean addListener(Listener<Block> listener, Event eventType) {
        return blockListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<Block> listener, Event eventType) {
        return blockListeners.removeListener(listener, eventType);
    }

    @Override
    public Peer getLastBlockchainFeeder() {
        return lastBlockchainFeeder;
    }

    @Override
    public int getLastBlockchainFeederHeight() {
        return lastBlockchainFeederHeight;
    }

    @Override
    public boolean isScanning() {
        return isScanning;
    }

    @Override
    public void processPeerBlock(JSONObject request) throws NxtException {
        BlockImpl block = parseBlock(request);
        pushBlock(block);
    }

    @Override
    public void fullReset() {
        synchronized (blockchain) {
            Logger.logMessage("Deleting blockchain...");
            //BlockDb.deleteBlock(Genesis.GENESIS_BLOCK_ID); // fails with stack overflow in H2
            BlockDb.deleteAll();
            addGenesisBlock();
            scan();
        }
    }

    private void addBlock(BlockImpl block) {
        try (Connection con = Db.getConnection()) {
            try {
                System.out.println("开始将区块保存到数据库......");
                BlockDb.saveBlock(con, block);
                /*开始将区块设置为链中的最后一块*/
                System.out.println("开始将区块设置为链中的最后一块");
                blockchain.setLastBlock(block);
                con.commit();
                System.out.println("add 区块flush到数据库中.......");
                /*提交*/
            } catch (SQLException e) {

                System.out.println("添加区块失败.......开始回滚"+block);
                con.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    private void addGenesisBlock() {
        Logger.logMessage("BlockchainProcessorImpl 添加区块.....");
        if (BlockDb.hasBlock(Genesis.GENESIS_BLOCK_ID)) {
            Logger.logMessage("Genesis block already in database");
            return;
        }
        Logger.logMessage("Genesis block not in database, starting from scratch");
        try {
            SortedMap<Long, TransactionImpl> transactionsMap = new TreeMap<>();

            for (int i = 0; i < Genesis.GENESIS_RECIPIENTS.length; i++) {
                TransactionImpl transaction = new TransactionImpl(TransactionType.Payment.ORDINARY, 0, (short) 0, Genesis.CREATOR_PUBLIC_KEY,
                        Genesis.GENESIS_RECIPIENTS[i], Genesis.GENESIS_AMOUNTS[i] * Constants.ONE_NXT, 0, (String)null, Genesis.GENESIS_SIGNATURES[i]);
                transactionsMap.put(transaction.getId(), transaction);
            }

            MessageDigest digest = Crypto.sha256();
            for (Transaction transaction : transactionsMap.values()) {
                digest.update(transaction.getBytes());
            }

            BlockImpl genesisBlock = new BlockImpl(-1, 0, null, Constants.MAX_BALANCE_NQT, 0, transactionsMap.size() * 128, digest.digest(),
                    Genesis.CREATOR_PUBLIC_KEY, new byte[64], Genesis.GENESIS_BLOCK_SIGNATURE, null, new ArrayList<>(transactionsMap.values()));

            genesisBlock.setPrevious(null);

            addBlock(genesisBlock);

        } catch (NxtException.ValidationException e) {
            Logger.logMessage(e.getMessage());
            throw new RuntimeException(e.toString(), e);
        }
    }

    private byte[] calculateTransactionsChecksum() {
        Logger.logMessage("BlockchainProcessorImpl 开始计算checksum的值.....");
        MessageDigest digest = Crypto.sha256();
        try (Connection con = Db.getConnection();
             PreparedStatement pstmt = con.prepareStatement(
                     "SELECT * FROM transaction ORDER BY id ASC, timestamp ASC");
             DbIterator<TransactionImpl> iterator = blockchain.getTransactions(con, pstmt)) {
            while (iterator.hasNext()) {
                digest.update(iterator.next().getBytes());
            }
        } catch (SQLException e) {
            Logger.logMessage("BlockchainProcessorImpl checksum的值计算失败....");
            throw new RuntimeException(e.toString(), e);
        }
        Logger.logMessage("BlockchainProcessorImpl checksum的值计算完成.........");
        return digest.digest();
    }

    private void pushBlock(final BlockImpl block) throws BlockNotAcceptedException {
        Logger.logMessage("BlockchainProcessorImpl pushBlock......开始执行pushBlock");
        /*获取创世的纪元时间*/
        int curTime = Convert.getEpochTime();
        Logger.logMessage("BlockchainProcessorImpl pushBlock......开始执行pushBlock 获取时间....");
        synchronized (blockchain) {
            try {
                /*获取当前本地的最后一个区块*/
                BlockImpl previousLastBlock = blockchain.getLastBlock();
                    /*验证新添加的区块的前一个区块的id*/
                if (! previousLastBlock.getId().equals(block.getPreviousBlockId())) {
                    throw new BlockOutOfOrderException("Previous block id doesn't match");
                }
                /*验证版本号*/
               if (! verifyVersion(block, previousLastBlock.getHeight())) {
                    throw new BlockNotAcceptedException("Invalid version " + block.getVersion());
                }

                if (previousLastBlock.getHeight() == Constants.TRANSPARENT_FORGING_BLOCK) {
                    byte[] checksum = calculateTransactionsChecksum();
                    StringBuilder builder=new StringBuilder();
                   for (int i=0;i<checksum.length;i++){
                       builder.append(checksum[i]+",");
                   }
                   Logger.logMessage("checksum="+builder);

                    if (CHECKSUM_TRANSPARENT_FORGING == null) {
                        Logger.logMessage("Checksum calculated:\n" + Arrays.toString(checksum));
                    } else if (!Arrays.equals(checksum, CHECKSUM_TRANSPARENT_FORGING)) {
                        Logger.logMessage("Checksum failed at block " + Constants.TRANSPARENT_FORGING_BLOCK);
                        throw new BlockNotAcceptedException("Checksum failed");
                    } else {
                        Logger.logMessage("Checksum passed at block " + Constants.TRANSPARENT_FORGING_BLOCK);
                    }
                }

                if (previousLastBlock.getHeight() == Constants.NQT_BLOCK) {
                    byte[] checksum = calculateTransactionsChecksum();
                    if (CHECKSUM_NQT_BLOCK == null) {
                        Logger.logMessage("Checksum calculated:\n" + Arrays.toString(checksum));
                    } else if (!Arrays.equals(checksum, CHECKSUM_NQT_BLOCK)) {
                        Logger.logMessage("Checksum failed at block " + Constants.NQT_BLOCK);
                        throw new BlockNotAcceptedException("Checksum failed");
                    } else {
                        Logger.logMessage("Checksum passed at block " + Constants.NQT_BLOCK);
                    }
                }
                    /*验证版本号和前一个块的摘要是否等于当前块中保存的PreviousBlockHash*/
                if (block.getVersion() != 1 && ! Arrays.equals(Crypto.sha256().digest(previousLastBlock.getBytes()), block.getPreviousBlockHash())) {
                    throw new BlockNotAcceptedException("Previous block hash doesn't match");
                }
                /*验证时间戳*/
                if (block.getTimestamp() > curTime + 15 || block.getTimestamp() <= previousLastBlock.getTimestamp()) {
                    throw new BlockOutOfOrderException("Invalid timestamp: " + block.getTimestamp()
                            + " current time is " + curTime + ", previous block timestamp is " + previousLastBlock.getTimestamp());
                }
                /*验证ID，根据ID查找数据库中是否存在新生成的*/
                if (block.getId().equals(Long.valueOf(0L)) || BlockDb.hasBlock(block.getId())) {
                    throw new BlockNotAcceptedException("Duplicate block or invalid id");
                }
/*****************************************生成器验证和块验证有问题**********************************************/
                /*验证生成器的签名*/
                if (! block.verifyGenerationSignature()) {
                    Logger.logMessage("验证生成器签名失败....................................................................");
                    throw new BlockNotAcceptedException("Generation signature verification failed");
                }
                /*验证块的签名*/
                if (! block.verifyBlockSignature()) {
                    Logger.logMessage("验证块的签名失败.........................................................................");
                    throw new BlockNotAcceptedException("Block signature verification failed");
                }
/*********************************************************************************************************/
                /*创建和map来保存双花 Map<TransactionType, Set<String>>*/
                Map<TransactionType, Set<String>> duplicates = new HashMap<>();
                /*创建一个map来保存accumulatedAmounts*/
                Map<Long, Long> accumulatedAmounts = new HashMap<>();
                /*创建一个Map来保存accumulatedAssetQuantities*/
                Map<Long, Map<Long, Long>> accumulatedAssetQuantities = new HashMap<>();
                long calculatedTotalAmount = 0;
                long calculatedTotalFee = 0;
                MessageDigest digest = Crypto.sha256();
                /*开始验证区块的中的交易*/
                for (TransactionImpl transaction : block.getTransactions()) {

                    // cfb: Block 303 contains a transaction which expired before the block timestamp
                    /*验证区块的时间戳*/
                    if (transaction.getTimestamp() > curTime + 15 || transaction.getTimestamp() > block.getTimestamp() + 15
                            || (transaction.getExpiration() < block.getTimestamp() && previousLastBlock.getHeight() != 303)) {
                        throw new TransactionNotAcceptedException("Invalid transaction timestamp " + transaction.getTimestamp()
                                + " for transaction " + transaction.getStringId() + ", current time is " + curTime
                                + ", block timestamp is " + block.getTimestamp(), transaction);
                    }
                    /*判断交易是否在数据中存在*/
                    if (TransactionDb.hasTransaction(transaction.getId())) {
                        throw new TransactionNotAcceptedException("Transaction " + transaction.getStringId()
                                + " is already in the blockchain", transaction);
                    }
                    if (transaction.getReferencedTransactionFullHash() != null) {
                        if ((previousLastBlock.getHeight() < Constants.REFERENCED_TRANSACTION_FULL_HASH_BLOCK
                                && !TransactionDb.hasTransaction(Convert.fullHashToId(transaction.getReferencedTransactionFullHash())))
                                || (previousLastBlock.getHeight() >= Constants.REFERENCED_TRANSACTION_FULL_HASH_BLOCK
                                && !hasAllReferencedTransactions(transaction, transaction.getTimestamp(), 0))) {
                            throw new TransactionNotAcceptedException("Missing or invalid referenced transaction "
                                    + transaction.getReferencedTransactionFullHash()
                                    + " for transaction " + transaction.getStringId(), transaction);
                        }
                    }
                    /*验证区块*/
                    if (! transaction.verify()) {
                        throw new TransactionNotAcceptedException("Signature verification failed for transaction "
                                + transaction.getStringId() + " at height " + previousLastBlock.getHeight(), transaction);
                    }
                    /*验证交易的ID*/
                    if (transaction.getId().equals(Long.valueOf(0L))) {
                        throw new TransactionNotAcceptedException("Invalid transaction id", transaction);
                    }
                    /*验证交易是否在双化集合中*/
                    if (transaction.isDuplicate(duplicates)) {
                        throw new TransactionNotAcceptedException("Transaction is a duplicate: "
                                + transaction.getStringId(), transaction);
                    }
                    try {
                        /*验证交易的附件*/
                        transaction.validateAttachment();
                    } catch (NxtException.ValidationException e) {
                        throw new TransactionNotAcceptedException(e.getMessage(), transaction);
                    }

                    calculatedTotalAmount += transaction.getAmountNQT();

                    transaction.updateTotals(accumulatedAmounts, accumulatedAssetQuantities);

                    calculatedTotalFee += transaction.getFeeNQT();

                    digest.update(transaction.getBytes());

                }
                /*交易交易的TotalAmount和TotalFee*/
                if (calculatedTotalAmount != block.getTotalAmountNQT() || calculatedTotalFee != block.getTotalFeeNQT()) {
                    throw new BlockNotAcceptedException("Total amount or fee don't match transaction totals");
                }
                /*验证区块的摘要*/
                if (!Arrays.equals(digest.digest(), block.getPayloadHash())) {
                    throw new BlockNotAcceptedException("Payload hash doesn't match");
                }
                for (Map.Entry<Long, Long> accumulatedAmountEntry : accumulatedAmounts.entrySet()) {
                    Account senderAccount = Account.getAccount(accumulatedAmountEntry.getKey());
                    if (senderAccount.getBalanceNQT() < accumulatedAmountEntry.getValue()) {
                        throw new BlockNotAcceptedException("Not enough funds in sender account: " + Convert.toUnsignedLong(senderAccount.getId()));
                    }
                }

                for (Map.Entry<Long, Map<Long, Long>> accumulatedAssetQuantitiesEntry : accumulatedAssetQuantities.entrySet()) {
                    Account senderAccount = Account.getAccount(accumulatedAssetQuantitiesEntry.getKey());
                    for (Map.Entry<Long, Long> accountAccumulatedAssetQuantitiesEntry : accumulatedAssetQuantitiesEntry.getValue().entrySet()) {
                        Long assetId = accountAccumulatedAssetQuantitiesEntry.getKey();
                        Long quantityQNT = accountAccumulatedAssetQuantitiesEntry.getValue();
                        if (senderAccount.getAssetBalanceQNT(assetId) < quantityQNT) {
                            throw new BlockNotAcceptedException("Asset balance not sufficient in sender account " + Convert.toUnsignedLong(senderAccount.getId()));
                        }
                    }
                }

                block.setPrevious(previousLastBlock);
                /*添加一个新的区块addBlock(block)*/
                addBlock(block);

            } catch (RuntimeException e) {
                Logger.logMessage("Error pushing block", e);
                throw new BlockNotAcceptedException(e.toString());
            }

            blockListeners.notify(block, Event.BEFORE_BLOCK_APPLY);
            /*开始apply(block)*/
            transactionProcessor.apply(block);
            blockListeners.notify(block, Event.AFTER_BLOCK_APPLY);
            blockListeners.notify(block, Event.BLOCK_PUSHED);
            Logger.logMessage("开始更新未确定交易的集合.......");
            /*更新未被证实的交易列表0*/
            transactionProcessor.updateUnconfirmedTransactions(block);

        } // synchronized
        /*如果区块的时间戳大于(getEpochTime() - 15)*/
        if (block.getTimestamp() >= Convert.getEpochTime() - 15) {
            Logger.logMessage("将生成的区块广播出去.......");
            Peers.sendToSomePeers(block);
        }

    }

    private boolean popLastBlock() throws TransactionType.UndoNotSupportedException {
        try {

            synchronized (blockchain) {
                /*获取最后一个区块*/
                BlockImpl block = blockchain.getLastBlock();
                Logger.logDebugMessage("Will pop block " + block.getStringId() + " at height " + block.getHeight());
                /*当块的ID等于创始块的ID时不再弹出区块*/
                if (block.getId().equals(Genesis.GENESIS_BLOCK_ID)) {
                    return false;
                }
                /*获取到当前节点最后一个区块的上一个区块*/
                BlockImpl previousBlock = BlockDb.findBlock(block.getPreviousBlockId());
                if (previousBlock == null) {
                    Logger.logMessage("Previous block is null");
                    throw new IllegalStateException();
                }
                //激活个一个事件BEFORE_BLOCK_UNDO
                blockListeners.notify(block, Event.BEFORE_BLOCK_UNDO);
                //将上一步获取到的区块设置为最后一个区块
                blockchain.setLastBlock(block, previousBlock);
                //开始执行取消区块上交易的
                transactionProcessor.undo(block);
                //从数据库中删除当前块id后的块
                BlockDb.deleteBlocksFrom(block.getId());
                //激活一个事件BLOCK_POPPE
                blockListeners.notify(block, Event.BLOCK_POPPED);
            } // synchronized

        } catch (RuntimeException e) {
            Logger.logDebugMessage("Error popping last block: " + e.getMessage());
            throw new TransactionType.UndoNotSupportedException(e.getMessage());
        }
        return true;
    }

    /**
     * 根据秘钥生成开始生成一个块
     * @param secretPhrase
     */
    void generateBlock(String secretPhrase) {
        Logger.logMessage("BlockchainProcessorImpl generateBlock.........");
        Set<TransactionImpl> sortedTransactions = new TreeSet<>();
        // 获取transactionProcessor中的未确定的交易，将确定的交易放进一个排序的列表里
        for (TransactionImpl transaction : transactionProcessor.getAllUnconfirmedTransactions()) {
            if (hasAllReferencedTransactions(transaction, transaction.getTimestamp(), 0)) {
                sortedTransactions.add(transaction);
            }
        }
        /*创建新的map集合来保存新的交易SortedMap<ID,trx>*/
        SortedMap<Long, TransactionImpl> newTransactions = new TreeMap<>();
        /*创建一个map集合来保存双花的交易,<keyType,Set<String>>*/
        Map<TransactionType, Set<String>> duplicates = new HashMap<>();
        /*创建一map来保存tx的累积量*/
        Map<Long, Long> accumulatedAmounts = new HashMap<>();

        long totalAmountNQT = 0;/*总的账号数量*/
        long totalFeeNQT = 0;/*总的手续费*/
        int payloadLength = 0;/*区块中交易的总字节数*/
        /*获取区块中的时间戳*/
        int blockTimestamp = Convert.getEpochTime();

        /*开始初始化块的信息*/
        while (payloadLength <= Constants.MAX_PAYLOAD_LENGTH) {
            /*获取当前放进newtrTx中的交易数量prevNumberOfNewTransactions=*/
            int prevNumberOfNewTransactions = newTransactions.size();
                /*处理放进已排序的交易的map中的交易*/
            for (TransactionImpl transaction : sortedTransactions) {
                /*获取交易的长度*/
                int transactionLength = transaction.getSize();
                /*判断交易是否在new transaction中和payLoadHash的值是否超过最大值*/
                if (newTransactions.get(transaction.getId()) != null || payloadLength + transactionLength > Constants.MAX_PAYLOAD_LENGTH) {
                    continue;
                }

                /*处理交易的时间戳或交易是否在区块生成时是否已经取消*/
                if (transaction.getTimestamp() > blockTimestamp + 15 || (transaction.getExpiration() < blockTimestamp)) {
                    continue;
                }

                /*判断交易是否已经双花*/
                if (transaction.isDuplicate(duplicates)) {

                    continue;
                }

                try {
                    /*验证交易的附件*/
                    transaction.validateAttachment();
                } catch (NxtException.ValidationException e) {
                    continue;
                }
                /*从交易中获取发送者ID*/
                Long sender = transaction.getSenderId();
               /*从累积map中获去发送者是否存在*/
                Long accumulatedAmount = accumulatedAmounts.get(sender);
                if (accumulatedAmount == null) {
                    /*如果发送者不存。将accumulatedAmount设置0*/
                    accumulatedAmount = 0L;
                }

                try {
                    /*开始计算余额AmountNQT+FeeNQT*/
                    long amount = Convert.safeAdd(transaction.getAmountNQT(), transaction.getFeeNQT());
                    /*判断(accumulatedAmount+amount)是否存在大于发送者的账户余额*/
                    if (Convert.safeAdd(accumulatedAmount, amount) > Account.getAccount(sender).getBalanceNQT()) {
                        continue;
                    }
                    /*将发送者放进累积map中<sender,(accumulatedAmount+amount)>*/
                    accumulatedAmounts.put(sender, Convert.safeAdd(accumulatedAmount, amount));
                } catch (ArithmeticException e) {
                    Logger.logDebugMessage("Transaction " + transaction.getStringId() + " causes overflow, skipping", e);
                    continue;
                }
                /*将tx放进newTransactions*/
                newTransactions.put(transaction.getId(), transaction);
                /*计算块的payloadLength*/
                payloadLength += transactionLength;
                /*计算块的AmountNQT*/
                totalAmountNQT += transaction.getAmountNQT();
                /*计算块的totalFeeNQT*/
                totalFeeNQT += transaction.getFeeNQT();

            }
            /*如果经过以上的操作后，newTransactions.size()的数量等于prevNumberOfNewTransactions才说明交易处理完毕*/
            if (newTransactions.size() == prevNumberOfNewTransactions) {
                break;
            }
        }
        /*根据私钥获取公钥*/
        final byte[] publicKey = Crypto.getPublicKey(secretPhrase);
        if (publicKey!=null){
            StringBuilder builder=new StringBuilder();

         for (byte b:publicKey){
             builder.append(b+",");
         }

            Logger.logMessage("BlockChianProcessorImpl publickey="+builder);
        }
        MessageDigest digest = Crypto.sha256();
        /*开始对交易进行加密*/
        for (Transaction transaction : newTransactions.values()) {
            /*更新区块中交易的摘要*/
            digest.update(transaction.getBytes());
        }
        /*重置摘要，计算hash*/
        byte[] payloadHash = digest.digest();
        /*获取最后一个区块*/
        BlockImpl previousBlock = blockchain.getLastBlock();
        if (previousBlock.getHeight() < Constants.TRANSPARENT_FORGING_BLOCK) {
            Logger.logDebugMessage("Generate block below " + Constants.TRANSPARENT_FORGING_BLOCK + " no longer supported");
            return;
        }
        /*使用前一个区块的签名字节数组来更新摘要*/
        digest.update(previousBlock.getGenerationSignature());
        /*根据公钥字节数组来获取签名(获取一个摘要或是has值)*/
        byte[] generationSignature = digest.digest(publicKey);

        Logger.logMessage("根据公钥生成块的签名");
        StringBuilder generationSig=new StringBuilder();
        for (int i=0;i<generationSignature.length;i++){
            generationSig.append(generationSignature[i]+",");
        }
        Logger.logMessage("根据公钥生成块的签名generationSignature="+generationSig);
        BlockImpl block;
        /*开始生成区块的版本*/
        int version = previousBlock.getHeight() < Constants.TRANSPARENT_FORGING_BLOCK ? -1 : 1;
        /*获取前一个区块的Blockhash*/
        byte[] previousBlockHash = Crypto.sha256().digest(previousBlock.getBytes());

        try {

            block = new BlockImpl(version, blockTimestamp, previousBlock.getId(), totalAmountNQT, totalFeeNQT, payloadLength,
                        payloadHash, publicKey, generationSignature, null, previousBlockHash, new ArrayList<>(newTransactions.values()));

        } catch (NxtException.ValidationException e) {
            // shouldn't happen because all transactions are already validated
            Logger.logMessage("Error generating block", e);
            return;
        }
        /*根据私钥对新生成的区块进行签名*/
        block.sign(secretPhrase);
        /*设置新生成区块的前一个区块*/
        block.setPrevious(previousBlock);

        try {
            /*将区块放进链中*/
            pushBlock(block);
            blockListeners.notify(block, Event.BLOCK_GENERATED);
            Logger.logDebugMessage("Account " + Convert.toUnsignedLong(block.getGeneratorId()) + " generated block " + block.getStringId());
        } catch (TransactionNotAcceptedException e) {
            Logger.logDebugMessage("Generate block failed: " + e.getMessage());
            Transaction transaction = e.getTransaction();
            Logger.logDebugMessage("Removing invalid transaction: " + transaction.getStringId());
           /*删除未被证实的交易*/
            transactionProcessor.removeUnconfirmedTransactions(Collections.singletonList((TransactionImpl)transaction));
        } catch (BlockNotAcceptedException e) {
            Logger.logDebugMessage("Generate block failed: " + e.getMessage());
        }

    }

    private BlockImpl parseBlock(JSONObject blockData) throws NxtException.ValidationException {
        int version = ((Long)blockData.get("version")).intValue();
        int timestamp = ((Long)blockData.get("timestamp")).intValue();
        Long previousBlock = Convert.parseUnsignedLong((String) blockData.get("previousBlock"));
        long totalAmountNQT = ((Long)blockData.get("totalAmountNQT"));
        long totalFeeNQT = ((Long)blockData.get("totalFeeNQT"));
        int payloadLength = ((Long)blockData.get("payloadLength")).intValue();
        byte[] payloadHash = Convert.parseHexString((String) blockData.get("payloadHash"));
        byte[] generatorPublicKey = Convert.parseHexString((String) blockData.get("generatorPublicKey"));
        byte[] generationSignature = Convert.parseHexString((String) blockData.get("generationSignature"));
        byte[] blockSignature = Convert.parseHexString((String) blockData.get("blockSignature"));
        byte[] previousBlockHash = version == 1 ? null : Convert.parseHexString((String) blockData.get("previousBlockHash"));

        SortedMap<Long, TransactionImpl> blockTransactions = new TreeMap<>();
        JSONArray transactionsData = (JSONArray)blockData.get("transactions");
        for (Object transactionData : transactionsData) {
            TransactionImpl transaction = transactionProcessor.parseTransaction((JSONObject) transactionData);
            if (blockTransactions.put(transaction.getId(), transaction) != null) {
                throw new NxtException.ValidationException("Block contains duplicate transactions: " + transaction.getStringId());
            }
        }

        return new BlockImpl(version, timestamp, previousBlock, totalAmountNQT, totalFeeNQT, payloadLength, payloadHash, generatorPublicKey,
                generationSignature, blockSignature, previousBlockHash, new ArrayList<>(blockTransactions.values()));
    }

    private boolean verifyVersion(Block block, int currentHeight) {
  /*      return block.getVersion() ==
                (currentHeight < Constants.TRANSPARENT_FORGING_BLOCK ? 1
                        : currentHeight < Constants.NQT_BLOCK ? 2
                        : 3);*/
//return true;

        return block.getVersion() ==1;


    }

    private boolean hasAllReferencedTransactions(Transaction transaction, int timestamp, int count) {
        if (transaction.getReferencedTransactionFullHash() == null) {
            return timestamp - transaction.getTimestamp() < 60 * 1440 * 60 && count < 10;
        }
        transaction = TransactionDb.findTransactionByFullHash(transaction.getReferencedTransactionFullHash());
        return transaction != null && hasAllReferencedTransactions(transaction, timestamp, count + 1);
    }

    private volatile boolean validateAtScan = false;
    //private volatile boolean validateAtScan = true;
    void validateAtNextScan() {
        validateAtScan = true;
    }

    private void scan() {
        synchronized (blockchain) {
            isScanning = true;
            Logger.logMessage("Scanning blockchain...");
            if (validateAtScan) {
                Logger.logDebugMessage("Also verifying signatures and validating transactions...");
            }
            Account.clear();
            Alias.clear();
            Asset.clear();
            Generator.clear();
            Order.clear();
            Poll.clear();
            Trade.clear();
            Vote.clear();
            DigitalGoodsStore.clear();
            transactionProcessor.clear();
            try (Connection con = Db.getConnection(); PreparedStatement pstmt = con.prepareStatement("SELECT * FROM block ORDER BY db_id ASC")) {
                Long currentBlockId = Genesis.GENESIS_BLOCK_ID;
                BlockImpl currentBlock;
                ResultSet rs = pstmt.executeQuery();
                try {
                    while (rs.next()) {
                        currentBlock = BlockDb.loadBlock(con, rs);
                        if (! currentBlock.getId().equals(currentBlockId)) {
                            throw new NxtException.ValidationException("Database blocks in the wrong order!");
                        }
                        if (validateAtScan && ! currentBlockId.equals(Genesis.GENESIS_BLOCK_ID)) {
                            if (!currentBlock.verifyBlockSignature() || !currentBlock.verifyGenerationSignature()) {
                                throw new NxtException.ValidationException("Invalid block signature");
                            }
                            if (! verifyVersion(currentBlock, blockchain.getHeight())) {
                                throw new NxtException.ValidationException("Invalid block version");
                            }
                            for (TransactionImpl transaction : currentBlock.getTransactions()) {
                                if (!transaction.verify()) {
                                    throw new NxtException.ValidationException("Invalid transaction signature");
                                }
                                transaction.validateAttachment();
                            }
                        }
                        blockchain.setLastBlock(currentBlock);
                        blockListeners.notify(currentBlock, Event.BEFORE_BLOCK_APPLY);
                        transactionProcessor.apply(currentBlock);
                        blockListeners.notify(currentBlock, Event.AFTER_BLOCK_APPLY);
                        blockListeners.notify(currentBlock, Event.BLOCK_SCANNED);
                        currentBlockId = currentBlock.getNextBlockId();
                    }
                } catch (NxtException |RuntimeException e) {
                    //只有在是Nxt异常或运行时异常时执行，当出现sqL异常时说明程序本身没有问题
                    Logger.logDebugMessage(e.toString(), e);
                    Logger.logDebugMessage("Applying block " + Convert.toUnsignedLong(currentBlockId) + " failed, deleting from database");
                   //如果当前区块出现错误，说明整个区块都出现了错了。删除当前区块后的所有的区块
                    BlockDb.deleteBlocksFrom(currentBlockId);
                    scan();
                }
            } catch (SQLException e) {
                throw new RuntimeException(e.toString(), e);
            }
            validateAtScan = false;
            Logger.logMessage("...done");
            isScanning = false;
        } // synchronized
    }

}
