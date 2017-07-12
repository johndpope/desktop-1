package nxt;

import nxt.peer.Peer;
import nxt.peer.Peers;
import nxt.util.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class TransactionProcessorImpl implements TransactionProcessor {

    private static final TransactionProcessorImpl instance = new TransactionProcessorImpl();

    static TransactionProcessorImpl getInstance() {
        return instance;
    }

    /*创建一个map用来保存未确定的map*/
    private final ConcurrentMap<Long, TransactionImpl> unconfirmedTransactions = new ConcurrentHashMap<>();
    /*用一个集合保存所有未被确认的交易*/
    private final Collection<TransactionImpl> allUnconfirmedTransactions = Collections.unmodifiableCollection(unconfirmedTransactions.values());
    /*用一个map保存未被广播的数据*/
    private final ConcurrentMap<Long, TransactionImpl> nonBroadcastedTransactions = new ConcurrentHashMap<>();
    /*创建一个监听器来监听交易*/
    private final Listeners<List<Transaction>,Event> transactionListeners = new Listeners<>();
/*创建一线程用来删除未确认的交易*/
    private final Runnable removeUnconfirmedTransactionsThread = new Runnable() {

        @Override
        public void run() {
            Logger.logMessage(" TransactionProcessorImpl 删除所有的未被确认的交易");
            try {
                try {

                    int curTime = Convert.getEpochTime();
                    /*创键一个列表来保存删除未确认的交易*/
                    List<Transaction> removedUnconfirmedTransactions = new ArrayList<>();

                    synchronized (BlockchainImpl.getInstance()) {
                        Iterator<TransactionImpl> iterator = unconfirmedTransactions.values().iterator();
                        while (iterator.hasNext()) {
                            TransactionImpl transaction = iterator.next();
                            if (transaction.getExpiration() < curTime) {
                                iterator.remove();/**/
                                transaction.undoUnconfirmed();
                                removedUnconfirmedTransactions.add(transaction);
                            }
                        }
                    }

                    if (removedUnconfirmedTransactions.size() > 0) {
                        transactionListeners.notify(removedUnconfirmedTransactions, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
                    }

                } catch (Exception e) {
                    Logger.logMessage("Error removing unconfirmed transactions", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    private final Runnable rebroadcastTransactionsThread = new Runnable() {

        @Override
        public void run() {
            Logger.logMessage(" TransactionProcessorImpl 重新广播所有的交易");
            try {
                try {
                    List<Transaction> transactionList = new ArrayList<>();
                    int curTime = Convert.getEpochTime();
                    for (TransactionImpl transaction : nonBroadcastedTransactions.values()) {
                        if (TransactionDb.hasTransaction(transaction.getId()) || transaction.getExpiration() < curTime) {
                           //如果事务数据库中存在交易或交易已经过期则从nonBroadcastedTransaction中删除交易
                            nonBroadcastedTransactions.remove(transaction.getId());
                        } else if (transaction.getTimestamp() < curTime - 30) {
                            //如果交易的时间戳小于当前时间的30秒以前
                            transactionList.add(transaction);
                        }
                    }

                    if (transactionList.size() > 0) {
                        Logger.logMessage("TransactionProcessorImpl peer的个数大于0个....");
                        /*将交易广播出去*/
                        Peers.sendToSomePeers(transactionList);
                    }

                } catch (Exception e) {
                    Logger.logDebugMessage("Error in transaction re-broadcasting thread", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    private final Runnable processTransactionsThread = new Runnable() {

        private final JSONStreamAware getUnconfirmedTransactionsRequest;
        {
            JSONObject request = new JSONObject();
            request.put("requestType", "getUnconfirmedTransactions");
            getUnconfirmedTransactionsRequest = JSON.prepareRequest(request);
        }

        @Override
        public void run() {
            Logger.logMessage(" TransactionProcessorImpl 处理交易的线程......");
            try {
                try {
                    Peer peer = Peers.getAnyPeer(Peer.State.CONNECTED, true);
                    if (peer == null) {
                        return;
                    }
                    JSONObject response = peer.send(getUnconfirmedTransactionsRequest);
                    if (response == null) {
                        return;
                    }
                    JSONArray transactionsData = (JSONArray)response.get("unconfirmedTransactions");
                    if (transactionsData == null || transactionsData.size() == 0) {
                        return;
                    }
                    try {
                        processPeerTransactions(transactionsData, false);
                    } catch (RuntimeException e) {
                        peer.blacklist(e);
                    }
                } catch (Exception e) {
                    Logger.logDebugMessage("Error processing unconfirmed transactions from peer", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }
        }

    };

    private TransactionProcessorImpl() {
        Logger.logMessage(" TransactionProcessorImpl 创建交易处理的对象，同时启动一些调动线程.......");
        Logger.logMessage(" TransactionProcessorImpl 初始调度  每5秒执行一次处理交易的线程");
        ThreadPool.scheduleThread(processTransactionsThread, 5);/*每5秒执行一次*/
        Logger.logMessage(" TransactionProcessorImpl 初始调度  每1秒执行一次删除");
        ThreadPool.scheduleThread(removeUnconfirmedTransactionsThread, 1);
        Logger.logMessage(" TransactionProcessorImpl 初始调度  每60秒执行一次重新广播的交易");
        ThreadPool.scheduleThread(rebroadcastTransactionsThread, 60);
    }

    @Override
    public boolean addListener(Listener<List<Transaction>> listener, Event eventType) {
        Logger.logMessage("将交易监听器，添加到监听器中");
        return transactionListeners.addListener(listener, eventType);
    }

    @Override
    public boolean removeListener(Listener<List<Transaction>> listener, Event eventType) {
        return transactionListeners.removeListener(listener, eventType);
    }

    @Override
    public Collection<TransactionImpl> getAllUnconfirmedTransactions() {
        return allUnconfirmedTransactions;
    }

    @Override
    public Transaction getUnconfirmedTransaction(Long transactionId) {
        return unconfirmedTransactions.get(transactionId);
    }

    @Override
    public Transaction newTransaction(short deadline, byte[] senderPublicKey, Long recipientId,
                                      long amountNQT, long feeNQT, String referencedTransactionFullHash)
            throws NxtException.ValidationException {
        Logger.logMessage("TransactionProcessorImpl 创建一新的交易195");
        TransactionImpl transaction = new TransactionImpl(TransactionType.Payment.ORDINARY, Convert.getEpochTime(), deadline, senderPublicKey,
                recipientId, amountNQT, feeNQT, referencedTransactionFullHash, null);
        transaction.validateAttachment();
        return transaction;
    }

    @Override
    public Transaction newTransaction(short deadline, byte[] senderPublicKey, Long recipientId,
                                      long amountNQT, long feeNQT, String referencedTransactionFullHash, Attachment attachment)
            throws NxtException.ValidationException {
        Logger.logMessage("TransactionProcessorImpl 创建一新的交易206");
        TransactionImpl transaction = new TransactionImpl(attachment.getTransactionType(), Convert.getEpochTime(), deadline,
                senderPublicKey, recipientId, amountNQT, feeNQT, referencedTransactionFullHash, null);
        transaction.setAttachment(attachment);
        transaction.validateAttachment();
        return transaction;
    }

    /**
     * 广播交易
     * @param transaction
     * @throws NxtException.ValidationException
     */
    @Override
    public void broadcast(Transaction transaction) throws NxtException.ValidationException {
        if (! transaction.verify()) {
            throw new NxtException.ValidationException("Transaction signature verification failed");
        }
        Logger.logMessage("TransactionProcessorImpl 广播交易"+transaction);
        List<Transaction> validTransactions = processTransactions(Collections.singletonList((TransactionImpl) transaction), true);


        if (validTransactions.contains(transaction)) {
            nonBroadcastedTransactions.put(transaction.getId(), (TransactionImpl) transaction);
            Logger.logMessage("Accepted new transaction " + transaction.getStringId());
        } else {
            Logger.logMessage("Rejecting double spending transaction " + transaction.getStringId());
            throw new NxtException.ValidationException("Double spending transaction");
        }
        Logger.logMessage("TransactionProcessorImpl 广播完成....");
    }

    /**
     *  处理peer上的交易
     * @param request
     */
    @Override
    public void processPeerTransactions(JSONObject request) {
        Logger.logMessage("TransactionProcessorImpl ------ processPeerTransactions  处理peer上的交易");
        JSONArray transactionsData = (JSONArray)request.get("transactions");
        processPeerTransactions(transactionsData, true);
    }

    /**
     *
     * @param bytes
     * @return
     * @throws NxtException.ValidationException
     */
    @Override
    public Transaction parseTransaction(byte[] bytes) throws NxtException.ValidationException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        byte type = buffer.get();
        byte subtype = buffer.get();
        int timestamp = buffer.getInt();
        short deadline = buffer.getShort();
        byte[] senderPublicKey = new byte[32];
        buffer.get(senderPublicKey);
        Long recipientId = buffer.getLong();
        long amountNQT = buffer.getLong();
        long feeNQT = buffer.getLong();
        String referencedTransactionFullHash = null;
        byte[] referencedTransactionFullHashBytes = new byte[32];
        buffer.get(referencedTransactionFullHashBytes);
        if (Convert.emptyToNull(referencedTransactionFullHashBytes) != null) {
            referencedTransactionFullHash = Convert.toHexString(referencedTransactionFullHashBytes);
        }
        byte[] signature = new byte[64];
        buffer.get(signature);
        signature = Convert.emptyToNull(signature);

        TransactionType transactionType = TransactionType.findTransactionType(type, subtype);
        TransactionImpl transaction;
        transaction = new TransactionImpl(transactionType, timestamp, deadline, senderPublicKey, recipientId,
                amountNQT, feeNQT, referencedTransactionFullHash, signature);
        transactionType.loadAttachment(transaction, buffer);

        return transaction;
    }

    /**
     *  解析交易数据
     * @param transactionData
     * @return
     * @throws NxtException.ValidationException
     */
    TransactionImpl parseTransaction(JSONObject transactionData) throws NxtException.ValidationException {
        byte type = ((Long)transactionData.get("type")).byteValue();
        byte subtype = ((Long)transactionData.get("subtype")).byteValue();
        int timestamp = ((Long)transactionData.get("timestamp")).intValue();
        short deadline = ((Long)transactionData.get("deadline")).shortValue();
        byte[] senderPublicKey = Convert.parseHexString((String) transactionData.get("senderPublicKey"));
        Long recipientId = Convert.parseUnsignedLong((String) transactionData.get("recipient"));
        if (recipientId == null) recipientId = 0L; // ugly
        long amountNQT = (Long) transactionData.get("amountNQT");
        long feeNQT = (Long) transactionData.get("feeNQT");
        String referencedTransactionFullHash = (String) transactionData.get("referencedTransactionFullHash");
        // ugly, remove later:
        Long referencedTransactionId = Convert.parseUnsignedLong((String) transactionData.get("referencedTransaction"));
        if (referencedTransactionId != null && referencedTransactionFullHash == null) {
            Transaction referencedTransaction = Nxt.getBlockchain().getTransaction(referencedTransactionId);
            if (referencedTransaction != null) {
                referencedTransactionFullHash = referencedTransaction.getFullHash();
            }
        }
        //
        byte[] signature = Convert.parseHexString((String) transactionData.get("signature"));

        TransactionType transactionType = TransactionType.findTransactionType(type, subtype);
        TransactionImpl transaction = new TransactionImpl(transactionType, timestamp, deadline, senderPublicKey, recipientId,
                amountNQT, feeNQT, referencedTransactionFullHash, signature);

        JSONObject attachmentData = (JSONObject)transactionData.get("attachment");
        transactionType.loadAttachment(transaction, attachmentData);
        return transaction;
    }

    /**
     * 清理unconfirmedTransactions
     * 清理nonBroadcastedTransactions
     */
    void clear() {
        unconfirmedTransactions.clear();
        nonBroadcastedTransactions.clear();
    }

    /**
     *apply一个区块
     * @param block
     */
    void apply(BlockImpl block) {
        Logger.logMessage("开始apply(BlockImpl block)");
        Logger.logMessage("第一步:block.apply()");
        block.apply();
        for (TransactionImpl transaction : block.getTransactions()) {
            /*判断交易是否在unconfirmedTransactions，若在则执行 transaction.apply()，否在执行transaction.applyUnconfirmed();*/
            if (! unconfirmedTransactions.containsKey(transaction.getId())) {
                transaction.applyUnconfirmed();
            }
            //TODO: Phaser not yet implemented
            //Phaser.processTransaction(transaction);
            transaction.apply();
        }
    }

    /**
     * 撤销交易
     * @param block
     * @throws TransactionType.UndoNotSupportedException
     */
    void undo(BlockImpl block) throws TransactionType.UndoNotSupportedException {
        block.undo();//取消块上的操作
        //创建一个列表来保存未确定的交易
        List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
        for (TransactionImpl transaction : block.getTransactions()) {
            //将块中的交易保存到为确定的列表中
            unconfirmedTransactions.put(transaction.getId(), transaction);

            transaction.undo();
            //将当前取出的交易保存到addedUnconfirmedTransactions
            addedUnconfirmedTransactions.add(transaction);

        }
        if (addedUnconfirmedTransactions.size() > 0) {
            //当块中的交易存在时就激活一个事件
            transactionListeners.notify(addedUnconfirmedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
        }
    }

    /**
     * 更新未确定的交易
     * @param block
     */
    void updateUnconfirmedTransactions(BlockImpl block) {
        List<Transaction> addedConfirmedTransactions = new ArrayList<>();
        List<Transaction> removedUnconfirmedTransactions = new ArrayList<>();

        for (Transaction transaction : block.getTransactions()) {
            addedConfirmedTransactions.add(transaction);
            Transaction removedTransaction = unconfirmedTransactions.remove(transaction.getId());
            if (removedTransaction != null) {
                removedUnconfirmedTransactions.add(removedTransaction);
            }
        }

        Iterator<TransactionImpl> iterator = unconfirmedTransactions.values().iterator();
        while (iterator.hasNext()) {
            TransactionImpl transaction = iterator.next();
            transaction.undoUnconfirmed();
            if (! transaction.applyUnconfirmed()) {
                iterator.remove();
                removedUnconfirmedTransactions.add(transaction);
                transactionListeners.notify(Collections.singletonList((Transaction)transaction), Event.ADDED_DOUBLESPENDING_TRANSACTIONS);
            }
        }

        if (removedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(removedUnconfirmedTransactions, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
        }
        if (addedConfirmedTransactions.size() > 0) {
            transactionListeners.notify(addedConfirmedTransactions, Event.ADDED_CONFIRMED_TRANSACTIONS);
        }

    }

    /**
     * 删除未被确认的交易
     * @param transactions
     */
    void removeUnconfirmedTransactions(Collection<TransactionImpl> transactions) {
        List<Transaction> removedList = new ArrayList<>();
        for (TransactionImpl transaction : transactions) {
            if (unconfirmedTransactions.remove(transaction.getId()) != null) {
                transaction.undoUnconfirmed();
                removedList.add(transaction);
            }
        }
        transactionListeners.notify(removedList, Event.REMOVED_UNCONFIRMED_TRANSACTIONS);
    }

    void shutdown() {
        removeUnconfirmedTransactions(new ArrayList<>(unconfirmedTransactions.values()));
    }

    /**
     * 处理peer的交易
     * @param transactionsData
     * @param sendToPeers
     */
    private void processPeerTransactions(JSONArray transactionsData, final boolean sendToPeers) {
        List<TransactionImpl> transactions = new ArrayList<>();
        for (Object transactionData : transactionsData) {
            try {
                transactions.add(parseTransaction((JSONObject) transactionData));
            } catch (NxtException.ValidationException e) {
                //if (! (e instanceof TransactionType.NotYetEnabledException)) {
                //    Logger.logDebugMessage("Dropping invalid transaction: " + e.getMessage());
                //}
            }
        }
        processTransactions(transactions, sendToPeers);
        for (TransactionImpl transaction : transactions) {
            nonBroadcastedTransactions.remove(transaction.getId());
        }
    }

    /**
     * 处理交易，也控制交易是否要发送到网络上
     * @param transactions 交易
     * @param sendToPeers 是否发送到网络上
     * @return
     */
    private List<Transaction> processTransactions(List<TransactionImpl> transactions, final boolean sendToPeers) {
        Logger.logMessage("TransactionProcessorImpl processTransactions 处理一个交易 ");
        Logger.logMessage("TransactionProcessorImpl processTransactions 创建一个列表，保存发送目的地址 ");
        List<Transaction> sendToPeersTransactions = new ArrayList<>();
        Logger.logMessage("TransactionProcessorImpl processTransactions 创建一个列表，保存未被确认的交易");
        List<Transaction> addedUnconfirmedTransactions = new ArrayList<>();
        Logger.logMessage("TransactionProcessorImpl processTransactions 创建一个列表，保存双花交易的列表 ");
        List<Transaction> addedDoubleSpendingTransactions = new ArrayList<>();
        Logger.logMessage("TransactionProcessorImpl processTransactions 处理交易的数量为="+transactions.size());
        for (TransactionImpl transaction : transactions) {

            try {

                int curTime = Convert.getEpochTime();
                Logger.logMessage("TransactionProcessorImpl processTransactions 当前时为    "+curTime);
                if (transaction.getTimestamp() > curTime + 15 || transaction.getExpiration() < curTime
                        || transaction.getDeadline() > 1440) {
                    Logger.logMessage("TransactionProcessorImpl processTransactions 交易不满足条件");
                    continue;
                }

                synchronized (BlockchainImpl.getInstance()) {
                    Logger.logMessage("TransactionProcessorImpl processTransactions 满足交易，但是要求高度最后" +
                            "一个块的高度要大于Constants.NQT_BLOCK(该值已经被改为0)");
                    if (Nxt.getBlockchain().getHeight() < Constants.NQT_BLOCK) {
                        break; // not ready to process transactions
                    }

                    Long id = transaction.getId();

                    Logger.logMessage("TransactionProcessorImpl processTransactions 获取当前交易的ID="+id);
                    if (TransactionDb.hasTransaction(id) || unconfirmedTransactions.containsKey(id)
                            || ! transaction.verify()) {
                        Logger.logMessage("TransactionProcessorImpl processTransactions 当前交易的ID不在事务表中");
                        continue;
                    }

                    if (transaction.applyUnconfirmed()) {
                        if (sendToPeers) {
                            Logger.logMessage("将当前交易发送到peer网络....");
                            if (nonBroadcastedTransactions.containsKey(id)) {
                                Logger.logMessage(" TransactionProcessorImpl processTransactions 收到了回滚的交易  " + transaction.getStringId()
                                        + " that we generated, will not forward to peers");
                                nonBroadcastedTransactions.remove(id);
                            } else {
                                sendToPeersTransactions.add(transaction);
                            }
                        }

                        unconfirmedTransactions.put(id, transaction);
                        addedUnconfirmedTransactions.add(transaction);
                    } else {
                        addedDoubleSpendingTransactions.add(transaction);
                    }
                }

            } catch (RuntimeException e) {
                Logger.logMessage("Error processing transaction", e);
            }

        }

        Logger.logMessage("发送到peer网络中的交易数目为="+sendToPeersTransactions.size());
        if (sendToPeersTransactions.size() > 0) {
            Peers.sendToSomePeers(sendToPeersTransactions);
        }

        if (addedUnconfirmedTransactions.size() > 0) {
            transactionListeners.notify(addedUnconfirmedTransactions, Event.ADDED_UNCONFIRMED_TRANSACTIONS);
        }
        if (addedDoubleSpendingTransactions.size() > 0) {
            transactionListeners.notify(addedDoubleSpendingTransactions, Event.ADDED_DOUBLESPENDING_TRANSACTIONS);
        }
        return addedUnconfirmedTransactions;
    }

}
