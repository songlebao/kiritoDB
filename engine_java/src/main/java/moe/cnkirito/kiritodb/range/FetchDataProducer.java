package moe.cnkirito.kiritodb.range;

import moe.cnkirito.directio.DirectIOLib;
import moe.cnkirito.directio.DirectIOUtils;
import moe.cnkirito.kiritodb.KiritoDB;
import moe.cnkirito.kiritodb.common.Constant;
import moe.cnkirito.kiritodb.data.CommitLog;
import net.openhft.affinity.AffinityLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class FetchDataProducer {

    public final static Logger logger = LoggerFactory.getLogger(FetchDataProducer.class);

    private int windowsNum;
    private CacheItem[] cacheItems;
    private CommitLog[] commitLogs;
    private Lock lock;

    public FetchDataProducer(KiritoDB kiritoDB) {
        lock = new ReentrantLock();
        int expectedNumPerPartition = kiritoDB.commitLogs[0].getFileLength();
        for (int i = 1; i < Constant.partitionNum; i++) {
            expectedNumPerPartition = Math.max(kiritoDB.commitLogs[i].getFileLength(), expectedNumPerPartition);
        }
        if (expectedNumPerPartition < 64000) {
            windowsNum = 4;
        } else {
            windowsNum = 1;
        }
        cacheItems = new CacheItem[windowsNum];
        for (int i = 0; i < windowsNum; i++) {
            CacheItem cacheItem = new CacheItem();
            if (DirectIOLib.binit) {
                cacheItem.buffer = DirectIOUtils.allocateForDirectIO(Constant.directIOLib, expectedNumPerPartition * Constant.VALUE_LENGTH);
            } else {
                cacheItem.buffer = ByteBuffer.allocateDirect(expectedNumPerPartition * Constant.VALUE_LENGTH);
            }
            cacheItems[i] = cacheItem;
        }
        this.commitLogs = kiritoDB.commitLogs;
    }

    public void startFetch() {
        for (int threadNo = 0; threadNo < windowsNum; threadNo++) {
            final int threadPartition = threadNo;
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (final AffinityLock al2 = AffinityLock.acquireLock()) {
                        for (int i = 0; i < Constant.partitionNum / windowsNum; i++) {
                            int dbIndex = i * windowsNum + threadPartition;
                            CacheItem cacheItem;
                            while (true) {
                                cacheItem = getCacheItem(dbIndex);
                                if (cacheItem != null) {
                                    break;
                                }
                            }
                            commitLogs[dbIndex].loadAll(cacheItem.buffer);
                            cacheItem.ready = true;
                            while (true) {
                                if (cacheItem.allReach) {
                                    break;
                                }
                            }
                            release(dbIndex);
                        }
                    } catch (IOException e) {
                        logger.error("threadNo{} load failed", threadPartition, e);
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }
    }

    public CacheItem getCacheItem(int dbIndex) {
        int index = dbIndex % windowsNum;
        lock.lock();
        if (cacheItems[index].dbIndex == dbIndex) {
            cacheItems[index].useRef++;
            if (cacheItems[index].useRef == 64 + 1) {
                cacheItems[index].allReach = true;
            }
            lock.unlock();
            return cacheItems[index];
        }

        if (cacheItems[index].useRef > 0) {
            lock.unlock();
            return null;
        }

        cacheItems[index].useRef = 1;
        cacheItems[index].ready = false;
        cacheItems[index].dbIndex = dbIndex;
        cacheItems[index].allReach = false;

        lock.unlock();
        return cacheItems[index];
    }

    public void release(int dbIndex) {
        int index = dbIndex % windowsNum;
        lock.lock();
        cacheItems[index].useRef--;
        lock.unlock();
    }

    public void destroy() {
    }

    public void init() {
        for (int i = 0; i < windowsNum; i++) {
            cacheItems[i].dbIndex = -1;
            cacheItems[i].useRef = -1;
            cacheItems[i].ready = false;
            cacheItems[i].allReach = false;
        }
    }
}
