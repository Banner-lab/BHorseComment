package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @ClassName SimpleRedisLock
 * @Description TODO
 * @Author XMING
 * @Date 2023/5/5 16:11
 * @Version 1.0
 */
public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String lockPrefix = "lock:";
    private static final String UUID_PREFIX = UUID.randomUUID().toString().toString().replace("-", "");
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate,String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    /**
     * 为了防止出现锁误删的情况出现，将线程标识作为锁的内容
     * @param timeoutSec
     * @return
     */
    @Override
    public boolean lock(long timeoutSec) {
        long id = Thread.currentThread().getId();
        String val = UUID_PREFIX + id;
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(lockPrefix + name, val, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }

    @Override
    public void unlock() {
        String val = stringRedisTemplate.opsForValue().get(lockPrefix + name);
        String curVal = UUID_PREFIX + Thread.currentThread().getId();
        if(curVal.equals(val)){
            stringRedisTemplate.delete(lockPrefix + name);
        }
    }
}
