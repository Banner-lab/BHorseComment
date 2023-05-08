package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @ClassName RedisConfig
 * @Description TODO
 * @Author XMING
 * @Date 2023/5/6 9:00
 * @Version 1.0
 */
@Configuration
public class RedisConfig {
    @Bean
    public RedissonClient redissonClient(){
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.10.128:6379").setPassword("ASD1234ko");
        return Redisson.create(config);
    }
}
