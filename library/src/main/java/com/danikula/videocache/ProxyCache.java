package com.danikula.videocache;

import android.text.TextUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static com.danikula.videocache.Preconditions.checkNotNull;

/**
 * Proxy for {@link Source} with caching support ({@link Cache}).
 * <p/>
 * Can be used only for sources with persistent data (that doesn't change with time).
 * Method {@link #read(byte[], long, int)} will be blocked while fetching data from source.
 * Useful for streaming something with caching e.g. streaming video/audio etc.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
class ProxyCache {

    private static final int MAX_READ_SOURCE_ATTEMPTS = 1;

    private final Source source;
    private final Cache fileCache;
    private final Object wc = new Object();
    private final Object rc = new Object();
    private final Object stopLock = new Object();
    private final AtomicInteger readSourceErrorsCount;
    private volatile Thread sourceReaderThread;
    private volatile boolean stopped;
    private volatile int percentsAvailable = -1;
    private final Config config;

    public ProxyCache(Source source, Cache cache,Config config) {
        this.source = checkNotNull(source);
        this.fileCache = checkNotNull(cache);
        this.readSourceErrorsCount = new AtomicInteger();
        this.config=config;
    }

    public int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
        ProxyCacheUtils.assertBuffer(buffer, offset, length);
        KLog.i("=======读取流，offset：" + offset + " length:" + length + " ,stopped:" + stopped);
        //通过wc锁的唤醒循环读取本地已缓存的视频文件
        // 如果本地已缓存的文件大小，大于播放需要的大小，则跳出while循环，将本地文件已缓存的部分给播放器
        while (!fileCache.isCompleted() && fileCache.available() < (offset + length) && !stopped) {
            //开启异步读取线程
            readSourceAsync();
            waitForSourceData();
            checkReadSourceErrorsCount();
        }
        //读取本地已缓存视频文件送给播放器
        int read = fileCache.read(buffer, offset, length);
        KLog.i("=======FileCache read，offset：" + offset + " ,length:" + length + " ,read:" + read);
        if (fileCache.isCompleted() && percentsAvailable != 100) {
            percentsAvailable = 100;
            onCachePercentsAvailableChanged(100);
        }
        return read;
    }

    private void checkReadSourceErrorsCount() throws ProxyCacheException {
        int errorsCount = readSourceErrorsCount.get();
        KLog.i("=====checkReadSourceErrorsCount:" + errorsCount);
        if (errorsCount >= MAX_READ_SOURCE_ATTEMPTS) {
            readSourceErrorsCount.set(0);
            KLog.i("=====检查到读取网络数据源出错，需要抛异常，结束下载");
            throw new ProxyCacheException("Error reading source " + errorsCount + " times");
        }
    }

    public void shutdown() {
        synchronized (stopLock) {
            KLog.i("Shutdown proxy for " + source);
            try {
                stopped = true;
                if (sourceReaderThread != null) {
                    sourceReaderThread.interrupt();
                }
                fileCache.close();
            } catch (ProxyCacheException e) {
                onError(e);
            }
        }
    }

    private synchronized void readSourceAsync() throws ProxyCacheException {
        boolean readingInProgress = sourceReaderThread != null && sourceReaderThread.getState() != Thread.State.TERMINATED;
        KLog.i("=====readSourceAsync，readingInProgress：" + readingInProgress);
        if (!stopped && !fileCache.isCompleted()) {
            if(!readingInProgress){
                sourceReaderThread = new Thread(new SourceReaderRunnable(), "Source reader for " + source);
                sourceReaderThread.start();
            }else{
                notifyNeedReadSourceData();
            }
        }
    }

    /**
     * 等待网络原始数据，通过{@link #notifyNewCacheDataAvailable(long, long)}方法唤醒
     *
     * @throws ProxyCacheException
     */
    private void waitForSourceData() throws ProxyCacheException {
        //等待原始数据，1000秒超时
        synchronized (wc) {
            KLog.i("===waitForSourceData");
            try {
                wc.wait(1000);
            } catch (InterruptedException e) {
                throw new ProxyCacheException("Waiting source data is interrupted!", e);
            }
        }
    }

    /**
     * 通知文件可用大小
     *
     * @param cacheAvailable
     * @param sourceAvailable
     */
    private void notifyNewCacheDataAvailable(long cacheAvailable, long sourceAvailable) {
        onCacheAvailable(cacheAvailable, sourceAvailable);

        synchronized (wc) {
            wc.notifyAll();
        }
    }

    private void notifyNeedReadSourceData() {
        synchronized (rc) {
            KLog.i("===notifyNeedReadSourceData");
            rc.notifyAll();
        }
    }

    private void waitForCacheConsumed() throws ProxyCacheException {
        //等待原始数据，1000秒超时
        synchronized (rc) {
            KLog.i("===waitForCacheConsumed");
            try {
                rc.wait();
            } catch (InterruptedException e) {
                throw new ProxyCacheException("Waiting cache consumed is interrupted!", e);
            }
        }
    }

    protected void onCacheAvailable(long cacheAvailable, long sourceLength) {
        boolean zeroLengthSource = sourceLength == 0;
        int percents = zeroLengthSource ? 100 : (int) ((float) cacheAvailable / sourceLength * 100);
        boolean percentsChanged = percents != percentsAvailable;
        boolean sourceLengthKnown = sourceLength >= 0;
        if (sourceLengthKnown && percentsChanged) {
            onCachePercentsAvailableChanged(percents);
        }
        percentsAvailable = percents;
    }

    protected void onCachePercentsAvailableChanged(int percentsAvailable) {
    }

    private void readSource() {
        KLog.i("=====从原始网络地址读取文件");
        long sourceAvailable = -1;
        long offset = 0;
        long minWaitLimit=500 *1024;
        long totalBytesRead=0;
        long maxRate=100*1024;
        if(config!=null && config.normalDownloadRate>0){
            maxRate=config.normalDownloadRate;
        }
        try {
            offset = fileCache.available();
            source.open(offset);
            sourceAvailable = source.length();

            long bifFileSize=6*1024*1024;
            long bigFileMinDownloadSecond=120;
            if(config!=null && config.bigFileSize>0 && config.bigFileDownloadMinSecond>0){
                bifFileSize=config.bigFileSize;
                bigFileMinDownloadSecond=config.bigFileDownloadMinSecond;
            }
            if(sourceAvailable>bifFileSize){
                long wantRate=sourceAvailable/bigFileMinDownloadSecond;
                if(wantRate>maxRate){
                    maxRate=wantRate;
                }
            }
            long timeStart=System.currentTimeMillis();

            byte[] buffer = new byte[ProxyCacheUtils.DEFAULT_BUFFER_SIZE];
            int readBytes;
            while ((readBytes = source.read(buffer)) != -1) {
                synchronized (stopLock) {
                    if (isStopped()) {
                        return;
                    }
                    //将读取到的网络数据流写到本地文件
                    fileCache.append(buffer, readBytes);
                }
                offset += readBytes;
                totalBytesRead += readBytes;
                KLog.i("=====文件正在下载，已下载：" + offset);
                notifyNewCacheDataAvailable(offset, sourceAvailable);

                long timeCurrent=System.currentTimeMillis();
                long timeDiff=timeCurrent-timeStart;
                if(timeDiff>0){
                    long rate = totalBytesRead*1000/timeDiff;//byte每秒
                    if(rate>maxRate){
                        long sleep=(totalBytesRead*1000/maxRate-timeDiff);
                        if(sleep>60){
                            KLog.i("======文件下载sleep:"+sleep+",rate:"+rate+",maxRate:"+maxRate);
                            Thread.sleep(sleep);
                        }
                    }
                }

                long maxReadPosition=fileCache.getMaxReadPosition();
                long diff=offset - maxReadPosition;
                KLog.i("=====maxReadPosition:" + maxReadPosition+",offset:"+offset+",sourceAvailable:"+sourceAvailable+",diff:"+diff+",minWaitLimit:"+minWaitLimit);
                if((sourceAvailable-offset)>minWaitLimit){
                    //超时才会进来
                    waitForCacheConsumed();
                }
            }
            KLog.i("======文件下载结束");
            tryComplete();
            onSourceRead();
        } catch (Throwable e) {
            readSourceErrorsCount.incrementAndGet();
            KLog.i("=====读取网络数据源出错:" + e.getMessage());
            onError(e);
        } finally {
            closeSource();
            notifyNewCacheDataAvailable(offset, sourceAvailable);
        }
    }

    private void onSourceRead() {
        // guaranteed notify listeners after source read and cache completed
        percentsAvailable = 100;
        onCachePercentsAvailableChanged(percentsAvailable);
    }

    private void tryComplete() throws ProxyCacheException {
        synchronized (stopLock) {
            if (!isStopped() && fileCache.available() == source.length()) {
                fileCache.complete();
            }
        }
    }

    private boolean isStopped() {
        return Thread.currentThread().isInterrupted() || stopped;
    }

    private void closeSource() {
        KLog.i("======关闭数据流");
        try {
            source.close();
        } catch (ProxyCacheException e) {
            onError(new ProxyCacheException("Error closing source " + source, e));
        }
    }

    protected final void onError(final Throwable e) {
        boolean interruption = e instanceof InterruptedProxyCacheException;
        if (interruption) {
            KLog.i("ProxyCache is interrupted");
        } else {
            KLog.i("ProxyCache error", e);
        }
    }

    private class SourceReaderRunnable implements Runnable {

        @Override
        public void run() {
            readSource();
        }
    }
}
