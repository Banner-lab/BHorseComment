package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private final static ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        //Shop shop = queryWithPassThrougu(id);
        // 缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 逻辑过期
     * @param id
     * @return
     */
    public Shop queryWithLogicalExpire(Long id){
        String prefix = RedisConstants.CACHE_SHOP_KEY;
        String key = prefix + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(shopJson)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        if(expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean flag = tryLock(lockKey);
        // 获取锁成功
        if(flag){
            // 开启一个新的线程操作数据库重建缓存
            CACHE_REBUILD_EXECUTOR.submit(()->{
               saveShop2Redis(id,RedisConstants.LOCK_SHOP_TTL);
               unLock(lockKey);
            });
        }
        return shop;
    }
    public void saveShop2Redis(Long id,Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }

    // 获取锁
    private boolean tryLock(String key){
        Boolean getEd = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(getEd);
    }

    //释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    @Override
    @Transactional
    public Result updateShtop(Shop shop) {
       // 更新数据库
       updateById(shop);
       String key = RedisConstants.CACHE_SHOP_KEY + shop.getId();
       // 删除缓存
       stringRedisTemplate.delete(key);
       return Result.ok();
    }

    /**
     * 缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrougu(Long id){
        String prefix = RedisConstants.CACHE_SHOP_KEY;
        String key = prefix + id;
        //从redis缓存中查找商铺数据
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //redis缓存中有数据，直接返回给前端
        if(StrUtil.isNotBlank(jsonStr)){
            Shop shop = JSONUtil.toBean(jsonStr, Shop.class);
            return shop;
        }
        if(jsonStr != null){
            return null;
        }
        // 缓存未命中，查询数据库
        Shop shop = getById(id);
        // 店铺不存在
        if(shop == null){
            // 采用redis缓存空值对象的方式解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存入redis缓存中
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 缓存重建
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id){
        String prefix = RedisConstants.CACHE_SHOP_KEY;
        String key = prefix + id;
        //从redis缓存中查找商铺数据
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //redis缓存中有数据，直接返回给前端
        if(StrUtil.isNotBlank(jsonStr)){
            Shop shop = JSONUtil.toBean(jsonStr, Shop.class);
            return shop;
        }
        if(jsonStr != null){
            return null;
        }
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            // 获取互斥锁
            boolean flag = tryLock(lockKey);
            if (!flag) {
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 缓存未命中，查询数据库
            shop = getById(id);
            // 店铺不存在
            if (shop == null) {
                // 采用redis缓存空值对象的方式解决缓存穿透问题
                stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存入redis缓存中
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            unLock(lockKey);
        }
        unLock(lockKey);
        return shop;
    }
}
