package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.User;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import io.reactivex.internal.functions.ObjectHelper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Autowired
    private ISeckillVoucherService voucherService;
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    RedisIdWorker redisIdWorker;

    @Resource
    RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    // voucherOrder一初始化就开始执行
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true){
                //获取队列中的订单信息
               // try {
               //     VoucherOrder voucherOrder = orderTasks.take();
               //     handlerVoucherOrder(voucherOrder);
               // } catch (Exception e) {
               //     log.error("处理订单异常",e);
               // }
                try {
                    List<MapRecord<String, Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(list == null || list.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handlerVoucherOrder(voucherOrder);

                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                }catch (Exception e) {
                    log.error("处理订单异常",e);
                    handlerPendingList();
                }

            }
        }

        private void handlerPendingList() {
            while (true){
                //获取队列中的订单信息
                // try {
                //     VoucherOrder voucherOrder = orderTasks.take();
                //     handlerVoucherOrder(voucherOrder);
                // } catch (Exception e) {
                //     log.error("处理订单异常",e);
                // }
                try {
                    List<MapRecord<String, Object,Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1","c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if(list == null || list.isEmpty()){
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handlerVoucherOrder(voucherOrder);

                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                }catch (Exception e) {
                    log.error("处理pending-list异常",e);
                }
            }
        }

        public void handlerVoucherOrder(VoucherOrder voucherOrder) {
            Long userId = voucherOrder.getUserId();
            Long voucherId = voucherOrder.getVoucherId();
            RLock redisLock = redissonClient.getLock("order:" + userId);
                boolean lock = redisLock.tryLock();
                if(!lock){
                    log.error("不允许重复下单");
                    return ;
                }
                try {
                    proxy.createVoucherOrder(voucherOrder);
                }finally {
                    redisLock.unlock();
                }
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        UserDTO user = UserHolder.getUser();
        long orderId = redisIdWorker.nextId("order");
        Long userId = user.getId();
        //执行lua脚本，判断是否有优惠券购买资格
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId));
        int res = result.intValue();
        // 判断结果是否为0
        if(res != 0){
            return Result.fail(res == 1? "库存不足" : "不能重复下单");
        }
        //可以下单，将订单信息保存到阻塞队列

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        orderTasks.add(voucherOrder);

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }


    /**
     * 异步处理下单操作
     * @param
     * @return
     */
    //@Override
    //public Result seckillVoucher(Long voucherId) {
    //    UserDTO user = UserHolder.getUser();
    //    Long userId = user.getId();
    //    //执行lua脚本，判断是否有优惠券购买资格
    //    Long result = stringRedisTemplate.execute(
    //            SECKILL_SCRIPT,
    //            Collections.emptyList(),
    //            voucherId.toString(),
    //            userId.toString());
    //    int res = result.intValue();
    //    // 判断结果是否为0
    //    if(res != 0){
    //        return Result.fail(res == 1? "库存不足" : "不能重复下单");
    //    }
    //    //可以下单，将订单信息保存到阻塞队列
    //    long orderId = redisIdWorker.nextId("order");
    //    VoucherOrder voucherOrder = new VoucherOrder();
    //    voucherOrder.setId(orderId);
    //    voucherOrder.setUserId(userId);
    //    voucherOrder.setVoucherId(voucherId);
    //    orderTasks.add(voucherOrder);

    //    //获取代理对象
    //    proxy = (IVoucherOrderService) AopContext.currentProxy();

    //    return Result.ok(orderId);
    //}

    //@Override
    //public Result seckillVoucher(Long voucherId) {
    //    //查询优惠券
    //    SeckillVoucher seckillVoucher = voucherService.getById(voucherId);
    //    // 判断秒杀是否已经开始
    //    LocalDateTime beginTime = seckillVoucher.getBeginTime();
    //    if (beginTime.isAfter(LocalDateTime.now())) {
    //        return Result.fail("秒杀还未开始");
    //    }
    //    if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
    //        return Result.fail("秒杀已经结束");
    //    }
    //    // 检查库存
    //    if(seckillVoucher.getStock() < 1){
    //        return Result.fail("库存不足");
    //    }
    //    Long userId = UserHolder.getUser().getId();

    //    //SimpleRedisLock redisLock = new SimpleRedisLock(stringRedisTemplate, "voucher:"+userId);
    //    RLock redisLock = redissonClient.getLock("order:" + userId);
    //    boolean lock = redisLock.tryLock();
    //    if(!lock){
    //        return Result.fail("一个人只能下单一张券");
    //    }
    //    try {
    //        IVoucherOrderService iVoucherOrderService = (IVoucherOrderService) AopContext.currentProxy();//代理对象调用，事务管理
    //        return iVoucherOrderService.createVoucherOrder(voucherId);
    //    }finally {
    //        redisLock.unlock();
    //    }
    //    //synchronized (userId.toString().intern()) {//防止事务未提交锁就被释放
    //    //}
    //}
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder){
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getUserId();

        long orderId = redisIdWorker.nextId("order");
        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();
        if(count > 0){
            log.error("已经下单过了");
            return ;
        }
        //扣减库存
        boolean succeed = voucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0)//cas操作，修改前检查是否与查询出来的旧数据相等
                .update();
        if(!succeed){
            log.error("库存不足");
            return ;
        }

        save(voucherOrder);
    }
}
