package nxt;

import nxt.crypto.Crypto;
import nxt.util.*;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class Generator {

    public static enum Event {
        GENERATION_DEADLINE, START_FORGING, STOP_FORGING
    }

    private static final Listeners<Generator,Event> listeners = new Listeners<>();

    private static final ConcurrentMap<Long, Block> lastBlocks = new ConcurrentHashMap<>();
    private static final ConcurrentMap<Long, BigInteger> hits = new ConcurrentHashMap<>();

    private static final ConcurrentMap<String, Generator> generators = new ConcurrentHashMap<>();
    private static final Collection<Generator> allGenerators = Collections.unmodifiableCollection(generators.values());

    private static final Runnable generateBlockThread = new Runnable() {

        @Override
        public void run() {

            try {
                if (Nxt.getBlockchain().getLastBlock().getHeight() < Constants.TRANSPARENT_FORGING_BLOCK) {
                    return;
                }
                if (Nxt.getBlockchainProcessor().isScanning()) {
                    return;
                }
                try {
                    for (Generator generator : generators.values()) {
                        generator.forge();
                    }
                } catch (Exception e) {
                    Logger.logMessage("Error in block generation thread", e);
                }
            } catch (Throwable t) {
                Logger.logMessage("CRITICAL ERROR. PLEASE REPORT TO THE DEVELOPERS.\n" + t.toString());
                t.printStackTrace();
                System.exit(1);
            }

        }

    };

    static {
        ThreadPool.scheduleThread(generateBlockThread, 1);
    }

    static void init() {
    }

    static void clear() {
        lastBlocks.clear();
        hits.clear();
    }

    public static boolean addListener(Listener<Generator> listener, Event eventType) {
        return listeners.addListener(listener, eventType);
    }

    public static boolean removeListener(Listener<Generator> listener, Event eventType) {
        return listeners.removeListener(listener, eventType);
    }

    public static Generator startForging(String secretPhrase) {
        Logger.logMessage("开始锻造，用户提供了私钥，程序要生成私钥");
        byte[] publicKey = Crypto.getPublicKey(secretPhrase);
        StringBuilder pubkey=new StringBuilder();
       for (int i=0;i<publicKey.length;i++){

           pubkey.append(publicKey[i]+",");
       }
       Logger.logMessage("用户的公钥为"+pubkey);
        return startForging(secretPhrase, publicKey);
    }

    public static Generator startForging(String secretPhrase, byte[] publicKey) {
        /*根据公钥和私钥开始锻造*/
        Logger.logMessage("Generator 根据公钥和私钥开始段造,根据公钥获取用户账号账号");
        Account account = Account.getAccount(publicKey);
        if (account == null) {
            Logger.logMessage("用户的账号不存在......请检查是否创建了用户的账号");
            return null;
        }
        Logger.logMessage("根据私钥，公钥和账号生成一个生成器对象......");

        Generator generator = new Generator(secretPhrase, publicKey, account);

        Logger.logMessage("考虑到了轻钱包的概念，及一台机器上可能登陆了多个账号");
        Generator old = generators.putIfAbsent(secretPhrase, generator);
        if (old != null) {
            Logger.logDebugMessage("Account " + Convert.toUnsignedLong(account.getId()) + " is already forging");
            return old;
        }
        Logger.logMessage("开始唤醒一个线程进行锻造.......");
        listeners.notify(generator, Event.START_FORGING);
        Logger.logDebugMessage("Account " + Convert.toUnsignedLong(account.getId()) + " started forging, deadline "
                + generator.getDeadline() + " seconds");
        return generator;
    }

    public static Generator stopForging(String secretPhrase) {
        Generator generator = generators.remove(secretPhrase);
        if (generator != null) {
            lastBlocks.remove(generator.accountId);
            hits.remove(generator.accountId);
            Logger.logDebugMessage("Account " + Convert.toUnsignedLong(generator.getAccountId()) + " stopped forging");
            listeners.notify(generator, Event.STOP_FORGING);
        }
        return generator;
    }

    public static Generator getGenerator(String secretPhrase) {
        Logger.logMessage("Generator 根据私钥从map里取出创建的Generator的对象.....");
        return generators.get(secretPhrase);
    }

    public static Collection<Generator> getAllGenerators() {
        return allGenerators;
    }

    static long getHitTime(Account account, Block block) {
        return getHitTime(account.getEffectiveBalanceNXT(), getHit(account.getPublicKey(), block), block);
    }

    private static BigInteger getHit(byte[] publicKey, Block block) {
        Logger.logMessage("Generator 获取Hit 的值..");
        if (block.getHeight() < Constants.TRANSPARENT_FORGING_BLOCK) {
            Logger.logMessage("Generator 该块以下不支持透明锻造了.......");
            throw new IllegalArgumentException("Generator Not supported below Transparent Forging Block");
        }
        MessageDigest digest = Crypto.sha256();
        digest.update(block.getGenerationSignature());
        byte[] generationSignatureHash = digest.digest(publicKey);
        return new BigInteger(1, new byte[] {generationSignatureHash[7], generationSignatureHash[6], generationSignatureHash[5], generationSignatureHash[4], generationSignatureHash[3], generationSignatureHash[2], generationSignatureHash[1], generationSignatureHash[0]});
    }

    private static long getHitTime(long effectiveBalanceNXT, BigInteger hit, Block block) {
        Logger.logMessage("Generator 获取Hit的时间.....");
        return block.getTimestamp()
                + hit.divide(BigInteger.valueOf(block.getBaseTarget())
                .multiply(BigInteger.valueOf(effectiveBalanceNXT))).longValue();
    }


    private final Long accountId;
    private final String secretPhrase;
    private final byte[] publicKey;
    private volatile long deadline;

    private Generator(String secretPhrase, byte[] publicKey, Account account) {
        this.secretPhrase = secretPhrase;
        this.publicKey = publicKey;
        // need to store publicKey in addition to accountId, because the account may not have had its publicKey set yet
        this.accountId = account.getId();
        Logger.logMessage("Generator 初始构造函数 accountId="+this.accountId);
        forge(); // initialize deadline
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public Long getAccountId() {
        return accountId;
    }

    public long getDeadline() {
        return deadline;
    }

    private void  forge() {
        Logger.logMessage("Generator forger()--问题在此处比较多.......Class Generator()=======>forger");
        Logger.logMessage("在Generator初始化中调用了锻造方法");
        Account account = Account.getAccount(accountId);
        if (account == null) {
            Logger.logMessage("Generator forger 获取到的账号对象为null");
            return;
        }

        long effectiveBalance = account.getEffectiveBalanceNXT();
        Logger.logMessage("Generator forger effectiveBalance数量为="+effectiveBalance);
        if (effectiveBalance <= 0) {
            Logger.logMessage("Generator forger 有效的账号额度为0,不能锻造="+effectiveBalance);
            return;
        }

        Block lastBlock = Nxt.getBlockchain().getLastBlock();

        Logger.logMessage("Generator forger 获取到的最后一个块的高度为="+lastBlock.getHeight())
        ;
        if (lastBlock.getHeight() < Constants.NQT_BLOCK) {
            Logger.logDebugMessage("Forging below block " + Constants.NQT_BLOCK + " no longer supported");
            return;
        }
        //lastblocks
        if (! lastBlock.equals(lastBlocks.get(accountId))) {
            Logger.logMessage("Generator forger 最后一个块的对象等不等于根据ID获取的对象的块.....");
            BigInteger hit = getHit(publicKey, lastBlock);
            Logger.logMessage("Generator forger hit的值为="+hit);
            lastBlocks.put(accountId, lastBlock);
            hits.put(accountId, hit);

            deadline = Math.max(getHitTime(account.getEffectiveBalanceNXT(), hit, lastBlock) - Convert.getEpochTime(), 0);
            Logger.logMessage("获取到的dead line时间为"+deadline);
            listeners.notify(this, Event.GENERATION_DEADLINE);

        }
        Logger.logMessage("Generator forger 可以生成一个区块......");

        int elapsedTime = Convert.getEpochTime() - lastBlock.getTimestamp();

        if (elapsedTime > 0) {
            Logger.logMessage("Generator forger elapsedTime="+elapsedTime);
            BigInteger target = BigInteger.valueOf(lastBlock.getBaseTarget())
                    .multiply(BigInteger.valueOf(effectiveBalance))
                    .multiply(BigInteger.valueOf(elapsedTime));
            Logger.logMessage("Generator forger (elapsedTime)调整后的目标值为="+target);
            /* alter >*/

            Logger.logMessage("Gernerator .......hit的值是="+hits.get(accountId)+"target的值是="+target +" elapsedTime="+elapsedTime);
            Logger.logMessage("hit 和basetarget的差值="+hits.get(accountId).subtract(target));
            if (hits.get(accountId).compareTo(target) <0) {
                Logger.logMessage("Generator forger 生成一个区块....");
                BlockchainProcessorImpl.getInstance().generateBlock(secretPhrase);
            }
        }

    }

}
