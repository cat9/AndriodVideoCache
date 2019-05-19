package com.danikula.videocache;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

/**
 * Simple memory based {@link Cache} implementation.
 *
 * @author Alexey Danilov (danikula@gmail.com).
 */
public class ByteArrayCache implements Cache {

    private volatile byte[] data;
    private volatile boolean completed;

    private long maxReadPosition=0;

    public ByteArrayCache() {
        this(new byte[0]);
    }

    public ByteArrayCache(byte[] data) {
        this.data = Preconditions.checkNotNull(data);
    }

    @Override
    public int read(byte[] buffer, long offset, int length) throws ProxyCacheException {
        if (offset >= data.length) {
            return -1;
        }
        if (offset > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too long offset for memory cache " + offset);
        }
        int len= new ByteArrayInputStream(data).read(buffer, (int) offset, length);
        if(len>=0){
            maxReadPosition=Math.max(maxReadPosition,offset+len);
        }
        return len;
    }

    @Override
    public long available() throws ProxyCacheException {
        return data.length;
    }

    @Override
    public void append(byte[] newData, int length) throws ProxyCacheException {
        Preconditions.checkNotNull(data);
        Preconditions.checkArgument(length >= 0 && length <= newData.length);

        byte[] appendedData = Arrays.copyOf(data, data.length + length);
        System.arraycopy(newData, 0, appendedData, data.length, length);
        data = appendedData;
    }

    @Override
    public void close() throws ProxyCacheException {
    }

    @Override
    public void complete() {
        completed = true;
    }

    @Override
    public boolean isCompleted() {
        return completed;
    }

    @Override
    public synchronized long getMaxReadPosition(){
        return maxReadPosition;
    }
}
