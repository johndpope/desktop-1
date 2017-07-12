package nxt;

import nxt.crypto.Crypto;
import nxt.util.Convert;
import nxt.util.Listener;
import nxt.util.Listeners;
import nxt.util.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class Account {

    public static enum Event {
        BALANCE, /*账户余额*/
        UNCONFIRMED_BALANCE,/*未确认的余额*/
        ASSET_BALANCE,/*资产余额*/
        UNCONFIRMED_ASSET_BALANCE,/*处于冻结状态的资产余额*/
        LEASE_SCHEDULED,/*租约计划*/
        LEASE_STARTED, /*租约开始*/
        LEASE_ENDED/*租约结束*/
    }

    /*资产Bean*/
    public static class AccountAsset {

        public final Long accountId;/*账户ID*/
        public final Long assetId;/*资产ID*/
        public final Long quantityQNT;/*QNT数量*/

        private AccountAsset(Long accountId, Long assetId, Long quantityQNT) {
            this.accountId = accountId;
            this.assetId = assetId;
            this.quantityQNT = quantityQNT;
        }

    }

    /*账户租约。为了参加锻造*/
    public static class AccountLease {

        public final Long lessorId;/*出租人*/
        public final Long lesseeId;/*承租人*/
        public final int fromHeight;/*触发租约时块的高度*/
        public final int toHeight;/*租约结束时账户的ID*/

        private AccountLease(Long lessorId, Long lesseeId, int fromHeight, int toHeight) {
            this.lessorId = lessorId;
            this.lesseeId = lesseeId;
            this.fromHeight = fromHeight;
            this.toHeight = toHeight;
        }

    }

    static {

        Nxt.getBlockchainProcessor().addListener(new Listener<Block>() {
            @Override
            public void notify(Block block) {
                Logger.logMessage("BlockchainProcessor.Event.AFTER_BLOCK_APPLY");
                int height = block.getHeight();
                Iterator<Map.Entry<Long, Account>> iterator = leasingAccounts.entrySet().iterator();
                while (iterator.hasNext()) {
                    Account account = iterator.next().getValue();
                    if (height == account.currentLeasingHeightFrom) {
                        Account.getAccount(account.currentLesseeId).leaserIds.add(account.getId());
                        leaseListeners.notify(
                                new AccountLease(account.getId(), account.currentLesseeId, height, account.currentLeasingHeightTo),
                                Event.LEASE_STARTED);
                    } else if (height == account.currentLeasingHeightTo) {
                        Account.getAccount(account.currentLesseeId).leaserIds.remove(account.getId());
                        leaseListeners.notify(
                                new AccountLease(account.getId(), account.currentLesseeId, account.currentLeasingHeightFrom, height),
                                Event.LEASE_ENDED);
                        if (account.nextLeasingHeightFrom == Integer.MAX_VALUE) {
                            account.currentLeasingHeightFrom = Integer.MAX_VALUE;
                            account.currentLesseeId = null;
                            //iterator.remove();
                        } else {
                            account.currentLeasingHeightFrom = account.nextLeasingHeightFrom;
                            account.currentLeasingHeightTo = account.nextLeasingHeightTo;
                            account.currentLesseeId = account.nextLesseeId;
                            account.nextLeasingHeightFrom = Integer.MAX_VALUE;
                            account.nextLesseeId = null;
                            if (height == account.currentLeasingHeightFrom) {
                                Account.getAccount(account.currentLesseeId).leaserIds.add(account.getId());
                                leaseListeners.notify(
                                        new AccountLease(account.getId(), account.currentLesseeId, height, account.currentLeasingHeightTo),
                                        Event.LEASE_STARTED);
                            }
                        }
                    } else if (height == account.currentLeasingHeightTo + 1440) {
                        //keep expired leases for up to 1440 blocks to be able to handle block pop-off
                        iterator.remove();
                    }
                }
            }
        }, BlockchainProcessor.Event.AFTER_BLOCK_APPLY);

        Nxt.getBlockchainProcessor().addListener(new Listener<Block>() {

            @Override
            public void notify(Block block) {
                Logger.logMessage(" BlockchainProcessor.Event.BLOCK_POPPED");
                int height = block.getHeight();
                for (Account account : leasingAccounts.values()) {
                    if (height == account.currentLeasingHeightFrom || height == account.currentLeasingHeightTo) {
                        // hack
                        throw new RuntimeException("Undo of lease start or end o supported");
                    }
                }
            }
        }, BlockchainProcessor.Event.BLOCK_POPPED);

    }

    private static final int maxTrackedBalanceConfirmations = 2881;/*最大的余额确认*/
    private static final ConcurrentMap<Long, Account> accounts = new ConcurrentHashMap<>();
    private static final Collection<Account> allAccounts = Collections.unmodifiableCollection(accounts.values());
    private static final ConcurrentMap<Long, Account> leasingAccounts = new ConcurrentHashMap<>();

    private static final Listeners<Account,Event> listeners = new Listeners<>();

    private static final Listeners<AccountAsset,Event> assetListeners = new Listeners<>();

    private static final Listeners<AccountLease,Event> leaseListeners = new Listeners<>();

    public static boolean addListener(Listener<Account> listener, Event eventType) {
        return listeners.addListener(listener, eventType);
    }

    public static boolean removeListener(Listener<Account> listener, Event eventType) {
        return listeners.removeListener(listener, eventType);
    }

    public static boolean addAssetListener(Listener<AccountAsset> listener, Event eventType) {
        return assetListeners.addListener(listener, eventType);
    }

    public static boolean removeAssetListener(Listener<AccountAsset> listener, Event eventType) {
        return assetListeners.removeListener(listener, eventType);
    }

    public static boolean addLeaseListener(Listener<AccountLease> listener, Event eventType) {
        return leaseListeners.addListener(listener, eventType);
    }

    public static boolean removeLeaseListener(Listener<AccountLease> listener, Event eventType) {
        return leaseListeners.removeListener(listener, eventType);
    }

    public static Collection<Account> getAllAccounts() {
        return allAccounts;
    }

    public static Account getAccount(Long id) {
        return accounts.get(id);
    }

    public static Account getAccount(byte[] publicKey) {
        return accounts.get(getId(publicKey));
    }

    public static Long getId(byte[] publicKey) {
        byte[] publicKeyHash = Crypto.sha256().digest(publicKey);
        return Convert.fullHashToId(publicKeyHash);
    }

    static Account addOrGetAccount(Long id) {
        Logger.logMessage("获取或停啊姐一个账号");
        Logger.logMessage("获取或添加账户="+id);
        Account oldAccount = accounts.get(id);
        if (oldAccount == null) {
            Account account = new Account(id);
            oldAccount = accounts.putIfAbsent(id, account);
            return oldAccount != null ? oldAccount : account;
        } else {
            return oldAccount;
        }
    }

    static void clear() {
        accounts.clear();
        leasingAccounts.clear();
    }


    private final Long id;
    private final int height;
    private volatile byte[] publicKey;
    private volatile int keyHeight;
    private long balanceNQT;
    private long unconfirmedBalanceNQT;
    private long forgedBalanceNQT;
    private final List<GuaranteedBalance> guaranteedBalances = new ArrayList<>();

    private volatile int currentLeasingHeightFrom;/*当前租约起始*/
    private volatile int currentLeasingHeightTo;/*当前租约结束*/
    private volatile Long currentLesseeId;/*当前租户*/
    private volatile int nextLeasingHeightFrom;/*下一次租约开始时的高度*/
    private volatile int nextLeasingHeightTo;/*下一次租约结束时的高度*/
    private volatile Long nextLesseeId;/*下一个租户的ID*/
    private Set<Long> leaserIds = Collections.newSetFromMap(new ConcurrentHashMap<Long,Boolean>());

    private final Map<Long, Long> assetBalances = new HashMap<>();
    private final Map<Long, Long> unconfirmedAssetBalances = new HashMap<>();

    private volatile String name;
    private volatile String description;

    private Account(Long id) {
        if (! id.equals(Crypto.rsDecode(Crypto.rsEncode(id)))) {
            Logger.logMessage("CRITICAL ERROR: Reed-Solomon encoding fails for " + id);
        }
        this.id = id;
        this.height = Nxt.getBlockchain().getLastBlock().getHeight();
        currentLeasingHeightFrom = Integer.MAX_VALUE;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    void setAccountInfo(String name, String description) {
        this.name = Convert.emptyToNull(name.trim());
        this.description = Convert.emptyToNull(description.trim());
    }

    public synchronized byte[] getPublicKey() {
        Logger.logMessage("Account ....获取公钥 publickey="+publicKey);
        if (this.keyHeight == 0) {
            return null;
        }
        return publicKey;
    }

    public synchronized long getBalanceNQT() {
        return balanceNQT;
    }

    public synchronized long getUnconfirmedBalanceNQT() {
        return unconfirmedBalanceNQT;
    }

    public synchronized long getForgedBalanceNQT() {
        Logger.logMessage("Account 获取的锻造的有效额度为........="+forgedBalanceNQT);
        return forgedBalanceNQT;
    }

    public long getEffectiveBalanceNXT() {
        Logger.logMessage("Account  锻造相关：getEffectiveBalanceNXT  ");
        Block lastBlock = Nxt.getBlockchain().getLastBlock();
        Logger.logMessage("Account  获取到了最后一个区块 "+lastBlock);
 /*       if (lastBlock.getHeight() >= Constants.TRANSPARENT_FORGING_BLOCK_6
                && (publicKey == null || keyHeight == -1 || lastBlock.getHeight() - keyHeight <= 1440)) {

            return 0; // cfb: Accounts with the public key revealed less than 1440 blocks ago are not allowed to generate blocks
        }*/
        Logger.logMessage("Account 进入锻造逻辑1......");
        if (lastBlock.getHeight() < Constants.TRANSPARENT_FORGING_BLOCK_3
                && this.height < Constants.TRANSPARENT_FORGING_BLOCK_2) {

            if (this.height == 0) {
                Logger.logMessage("Account getEffectiveBalanceNXT="+getBalanceNQT() / Constants.ONE_NXT);
                return getBalanceNQT() / Constants.ONE_NXT;
            }
            /*以下为控制一个账号的锻造余额在生成1440块后又用*/
 /*           if (lastBlock.getHeight() - this.height < 1440) {
                return 0;
            }*/
            Logger.logMessage("Account 控制一个账户在高度为n后才能继续生成下一个块");

            long receivedInlastBlock = 0;
            for (Transaction transaction : lastBlock.getTransactions()) {
                if (transaction.getRecipientId().equals(id)) {
                    receivedInlastBlock += transaction.getAmountNQT();
                }
            }
            Logger.logMessage("Account .....getEffectiveBalanceNXT="+(getBalanceNQT() - receivedInlastBlock) / Constants.ONE_NXT);
            return (getBalanceNQT() - receivedInlastBlock) / Constants.ONE_NXT;
        }

/*进入锻造逻辑2*/
Logger.logMessage("Account 进入锻造逻辑2暂时不考虑租约-----");

   /*     if (lastBlock.getHeight() < currentLeasingHeightFrom) {
            Logger.logMessage("Account lastBlock.getHeight() < currentLeasingHeightFrom");
                return (getGuaranteedBalanceNQT(1440) + getExtraEffectiveBalanceNQT()) / Constants.ONE_NXT;
        }
*/

       // return getExtraEffectiveBalanceNQT() / Constants.ONE_NXT;

        return getBalanceNQT()/ Constants.ONE_NXT;/*new */
    }

    private long getExtraEffectiveBalanceNQT() {
        long extraEffectiveBalanceNQT = 0;
        for (Long accountId : leaserIds) {
            extraEffectiveBalanceNQT += Account.getAccount(accountId).getGuaranteedBalanceNQT(1440);
        }
        return extraEffectiveBalanceNQT;
    }

    public synchronized long getGuaranteedBalanceNQT(final int numberOfConfirmations) {
        /*如果确认数量大于区块的高度，则担保的账户余额为0*/
        if (numberOfConfirmations >= Nxt.getBlockchain().getLastBlock().getHeight()) {
            return 0;
        }
        if (numberOfConfirmations > maxTrackedBalanceConfirmations || numberOfConfirmations < 0) {
            throw new IllegalArgumentException("Number of required confirmations must be between 0 and " + maxTrackedBalanceConfirmations);
        }
        if (guaranteedBalances.isEmpty()) {
            return 0;
        }
        int i = Collections.binarySearch(guaranteedBalances, new GuaranteedBalance(Nxt.getBlockchain().getLastBlock().getHeight() - numberOfConfirmations, 0));
        if (i == -1) {
            return 0;
        }
        if (i < -1) {
            i = -i - 2;
        }
        if (i > guaranteedBalances.size() - 1) {
            i = guaranteedBalances.size() - 1;
        }
        GuaranteedBalance result;
        while ((result = guaranteedBalances.get(i)).ignore && i > 0) {
            i--;
        }
        return result.ignore || result.balance < 0 ? 0 : result.balance;

    }

    public synchronized Long getUnconfirmedAssetBalanceQNT(Long assetId) {
        return unconfirmedAssetBalances.get(assetId);
    }

    public Map<Long, Long> getAssetBalancesQNT() {
        return Collections.unmodifiableMap(assetBalances);
    }

    public Map<Long, Long> getUnconfirmedAssetBalancesQNT() {
        return Collections.unmodifiableMap(unconfirmedAssetBalances);
    }

    public Long getCurrentLesseeId() {
        return currentLesseeId;
    }

    public Long getNextLesseeId() {
        return nextLesseeId;
    }

    public int getCurrentLeasingHeightFrom() {
        return currentLeasingHeightFrom;
    }

    public int getCurrentLeasingHeightTo() {
        return currentLeasingHeightTo;
    }

    public int getNextLeasingHeightFrom() {
        return nextLeasingHeightFrom;
    }

    public int getNextLeasingHeightTo() {
        return nextLeasingHeightTo;
    }

    public Set<Long> getLeaserIds() {
        return Collections.unmodifiableSet(leaserIds);
    }

    void leaseEffectiveBalance(Long lesseeId, short period) {
        /*获取有效的租约账户余*/
        Account lessee = Account.getAccount(lesseeId);
        if (lessee != null && lessee.getPublicKey() != null) {
            Block lastBlock = Nxt.getBlockchain().getLastBlock();
            leasingAccounts.put(this.getId(), this);
            if (currentLeasingHeightFrom == Integer.MAX_VALUE) {

                currentLeasingHeightFrom = lastBlock.getHeight() + 1440;
                currentLeasingHeightTo = currentLeasingHeightFrom + period;
                currentLesseeId = lesseeId;
                nextLeasingHeightFrom = Integer.MAX_VALUE;
                leaseListeners.notify(
                        new AccountLease(this.getId(), lesseeId, currentLeasingHeightFrom, currentLeasingHeightTo),
                        Event.LEASE_SCHEDULED);

            } else {

                nextLeasingHeightFrom = lastBlock.getHeight() + 1440;
                if (nextLeasingHeightFrom < currentLeasingHeightTo) {
                    nextLeasingHeightFrom = currentLeasingHeightTo;
                }
                nextLeasingHeightTo = nextLeasingHeightFrom + period;
                nextLesseeId = lesseeId;
                leaseListeners.notify(
                        new AccountLease(this.getId(), lesseeId, nextLeasingHeightFrom, nextLeasingHeightTo),
                        Event.LEASE_SCHEDULED);

            }
        }
    }

    // returns true iff:
    // this.publicKey is set to null (in which case this.publicKey also gets set to key)
    // or
    // this.publicKey is already set to an array equal to key
    synchronized boolean setOrVerify(byte[] key, int height) {
        if (this.publicKey == null) {
            this.publicKey = key;
            this.keyHeight = -1;
            return true;
        } else if (Arrays.equals(this.publicKey, key)) {
            return true;
        } else if (this.keyHeight == -1) {
            Logger.logMessage("DUPLICATE KEY!!!");
            Logger.logMessage("Account key for " + Convert.toUnsignedLong(id) + " was already set to a different one at the same height "
                    + ", current height is " + height + ", rejecting new key");
            return false;
        } else if (this.keyHeight >= height) {
            Logger.logMessage("DUPLICATE KEY!!!");
            Logger.logMessage("Changing key for account " + Convert.toUnsignedLong(id) + " at height " + height
                    + ", was previously set to a different one at height " + keyHeight);
            this.publicKey = key;
            this.keyHeight = height;
            return true;
        }
        Logger.logMessage("DUPLICATE KEY!!!");
        Logger.logMessage("Invalid key for account " + Convert.toUnsignedLong(id) + " at height " + height
                + ", was already set to a different one at height " + keyHeight);
        return false;
    }

    /**
     *
     * @param key 一般为公钥
     * @param height
     */
    synchronized void apply(byte[] key, int height) {
        /*根据公钥个私钥进行验证*/
        if (! setOrVerify(key, this.height)) {
            throw new IllegalStateException("Generator public key mismatch");
        }
        if (this.publicKey == null) {
            throw new IllegalStateException("Public key has not been set for account " + Convert.toUnsignedLong(id)
                    +" at height " + height + ", key height is " + keyHeight);
        }
        if (this.keyHeight == -1 || this.keyHeight > height) {
            this.keyHeight = height;
        }
    }
/*根据高度取消*/
    synchronized void undo(int height) {
        if (this.keyHeight >= height) {
            Logger.logDebugMessage("Unsetting key for account " + Convert.toUnsignedLong(id) + " at height " + height
                    + ", was previously set at height " + keyHeight);
            this.publicKey = null;
            this.keyHeight = -1;
        }
        if (this.height == height) {
            Logger.logDebugMessage("Removing account " + Convert.toUnsignedLong(id) + " which was created in the popped off block");
            accounts.remove(this.getId());
        }
    }

    synchronized long getAssetBalanceQNT(Long assetId) {
        return Convert.nullToZero(assetBalances.get(assetId));
    }

    void addToAssetBalanceQNT(Long assetId, long quantityQNT) {
        synchronized (this) {
            Long assetBalance = assetBalances.get(assetId);
            if (assetBalance == null) {
                assetBalances.put(assetId, quantityQNT);
            } else {
                assetBalances.put(assetId, Convert.safeAdd(assetBalance, quantityQNT));
            }
        }
        listeners.notify(this, Event.ASSET_BALANCE);
        assetListeners.notify(new AccountAsset(id, assetId, assetBalances.get(assetId)), Event.ASSET_BALANCE);
    }

    void addToUnconfirmedAssetBalanceQNT(Long assetId, long quantityQNT) {
        synchronized (this) {
            Long unconfirmedAssetBalance = unconfirmedAssetBalances.get(assetId);
            if (unconfirmedAssetBalance == null) {
                unconfirmedAssetBalances.put(assetId, quantityQNT);
            } else {
                unconfirmedAssetBalances.put(assetId, Convert.safeAdd(unconfirmedAssetBalance, quantityQNT));
            }
        }
        listeners.notify(this, Event.UNCONFIRMED_ASSET_BALANCE);
        assetListeners.notify(new AccountAsset(id, assetId, unconfirmedAssetBalances.get(assetId)), Event.UNCONFIRMED_ASSET_BALANCE);
    }

    /**
     *
     * @param assetId 资产ID
     * @param quantityQNT NQT数量
     */
    void addToAssetAndUnconfirmedAssetBalanceQNT(Long assetId, long quantityQNT) {
        synchronized (this) {
            Long assetBalance = assetBalances.get(assetId);
            if (assetBalance == null) {
                assetBalances.put(assetId, quantityQNT);
            } else {
                assetBalances.put(assetId, Convert.safeAdd(assetBalance, quantityQNT));
            }
            Long unconfirmedAssetBalance = unconfirmedAssetBalances.get(assetId);
            if (unconfirmedAssetBalance == null) {
                unconfirmedAssetBalances.put(assetId, quantityQNT);
            } else {
                unconfirmedAssetBalances.put(assetId, Convert.safeAdd(unconfirmedAssetBalance, quantityQNT));
            }
        }
        listeners.notify(this, Event.ASSET_BALANCE);
        listeners.notify(this, Event.UNCONFIRMED_ASSET_BALANCE);
        assetListeners.notify(new AccountAsset(id, assetId, assetBalances.get(assetId)), Event.ASSET_BALANCE);
        assetListeners.notify(new AccountAsset(id, assetId, unconfirmedAssetBalances.get(assetId)), Event.UNCONFIRMED_ASSET_BALANCE);
    }

    void addToBalanceNQT(long amountNQT) {
        synchronized (this) {
            this.balanceNQT = Convert.safeAdd(this.balanceNQT, amountNQT);
            addToGuaranteedBalanceNQT(amountNQT);
        }
        if (amountNQT != 0) {
            listeners.notify(this, Event.BALANCE);
        }
    }

    void addToUnconfirmedBalanceNQT(long amountNQT) {
        if (amountNQT == 0) {
            return;
        }
        synchronized (this) {
            this.unconfirmedBalanceNQT = Convert.safeAdd(this.unconfirmedBalanceNQT, amountNQT);
        }
        listeners.notify(this, Event.UNCONFIRMED_BALANCE);
    }

    /**
     *
     * @param amountNQT
     */
    void addToBalanceAndUnconfirmedBalanceNQT(long amountNQT) {
        synchronized (this) {
            this.balanceNQT = Convert.safeAdd(this.balanceNQT, amountNQT);
            this.unconfirmedBalanceNQT = Convert.safeAdd(this.unconfirmedBalanceNQT, amountNQT);
            addToGuaranteedBalanceNQT(amountNQT);
        }
        if (amountNQT != 0) {
            listeners.notify(this, Event.BALANCE);
            listeners.notify(this, Event.UNCONFIRMED_BALANCE);
        }
    }

    /**
     * 将锻造获得的手续费添加到账户余额
     * @param amountNQT
     */
    void addToForgedBalanceNQT(long amountNQT) {

        synchronized(this) {
            this.forgedBalanceNQT = Convert.safeAdd(this.forgedBalanceNQT, amountNQT);
        }
    }

    /**
     * 将余额分配到账户
     * @param amountNQT
     */
    private synchronized void addToGuaranteedBalanceNQT(long amountNQT) {

        //获取最后一个区块
        int blockchainHeight = Nxt.getBlockchain().getLastBlock().getHeight();
        GuaranteedBalance last = null;
        /*-----------判断分配的账户，如果最后一个块被pop后------------*/
        if (guaranteedBalances.size() > 0 && (last = guaranteedBalances.get(guaranteedBalances.size() - 1)).height > blockchainHeight) {

            // this only happens while last block is being popped off
            if (amountNQT > 0) {
                // this is a reversal of a withdrawal or a fee, so previous gb records need to be corrected
                /*撤销交易使用*/
                for (GuaranteedBalance gb : guaranteedBalances) {
                    Logger.logMessage("从账户"+this.getId()+"====>"+"分发到账户"+gb);
                    gb.balance += amountNQT;/*将余额添加到账户*/
                    Logger.logMessage("从账户"+this.getId()+"====>"+"分发到账户后"+gb);
                }
            } // deposits don't need to be reversed as they have never been applied to old gb records to begin with
            last.ignore = true; // set dirty flag
            return; // block popped off, no further processing
        }
        /*一下开始裁剪*/
        int trimTo = 0;
        for (int i = 0; i < guaranteedBalances.size(); i++) {
            GuaranteedBalance gb = guaranteedBalances.get(i); /**/
            if (gb.height < blockchainHeight - maxTrackedBalanceConfirmations
                    && i < guaranteedBalances.size() - 1
                    && guaranteedBalances.get(i + 1).height >= blockchainHeight - maxTrackedBalanceConfirmations) {
                trimTo = i; // trim old gb records but keep at least one at height lower than the supported maxTrackedBalanceConfirmations
                if (blockchainHeight >= Constants.TRANSPARENT_FORGING_BLOCK_4 && blockchainHeight < Constants.TRANSPARENT_FORGING_BLOCK_5) {
                    gb.balance += amountNQT; // because of a bug which leads to a fork
                } else if (blockchainHeight >= Constants.TRANSPARENT_FORGING_BLOCK_5 && amountNQT < 0) {
                    gb.balance += amountNQT;
                }
            } else if (amountNQT < 0) {/*从所有的分派账户中撤回余额*/
                gb.balance += amountNQT; // subtract current block withdrawals from all previous gb records
            }
            // ignore deposits when updating previous gb records
        }
        if (trimTo > 0) {
            Iterator<GuaranteedBalance> iter = guaranteedBalances.iterator();
            while (iter.hasNext() && trimTo > 0) {
                iter.next();
                iter.remove();
                trimTo--;
            }
        }
        if (guaranteedBalances.size() == 0 || last.height < blockchainHeight) {
            // this is the first transaction affecting this account in a newly added block
            guaranteedBalances.add(new GuaranteedBalance(blockchainHeight, balanceNQT));
        } else if (last.height == blockchainHeight) {
            // following transactions for same account in a newly added block
            // for the current block, guaranteedBalance (0 confirmations) must be same as balance
            last.balance = balanceNQT;
            last.ignore = false;
        } else {
            // should have been handled in the block popped off case
            throw new IllegalStateException("last guaranteed balance height exceeds blockchain height");
        }
    }


    /**
     * 以下分类为对货币分发的封装
     *
     */
    private static class GuaranteedBalance implements Comparable<GuaranteedBalance> {

        final int height;
        long balance;
        boolean ignore;

        private GuaranteedBalance(int height, long balance) {
            this.height = height;
            this.balance = balance;
            this.ignore = false;
        }

        @Override
        public int compareTo(GuaranteedBalance o) {
            if (this.height < o.height) {
                return -1;
            } else if (this.height > o.height) {
                return 1;
            }
            return 0;
        }

        @Override
        public String toString() {
            return "height: " + height + ", guaranteed: " + balance;
        }
    }

}
