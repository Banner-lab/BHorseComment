package com.hmdp.utils;

/**
 *  分布式锁接口
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @return 成功获取锁返回true，否则返回false
     */
    boolean lock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
