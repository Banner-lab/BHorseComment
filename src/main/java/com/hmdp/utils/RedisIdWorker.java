package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @ClassName RedisIdWorker
 * @Description TODO
 * @Author XMING
 * @Date 2023/5/5 10:06
 * @Version 1.0
 */
@Component
public class RedisIdWorker {
    private static final int BEGIN_TIMESTAMP = 1683281415;
    private static final int COUNT_BITS = 32;
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long  nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = (nowSecond - BEGIN_TIMESTAMP) ;

        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+date, 1);
        return timestamp << COUNT_BITS | count;
    }


}
