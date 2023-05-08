-- 秒杀优化脚本
-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]

local orderId = ARGV[3]

-- 库存key
local stockKey = "seckill:stock:" .. voucherId

-- 订单key
local orderKey = "seckill:order:" .. voucherId

-- 判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <= 0) then
    return 1
end

-- 判断用户是否下单 SISMEMBER orderKey userId
if(redis.call('sismember',orderKey,userId) == 1) then
    return 2
end

-- 用户可以抢购优惠券
-- 库存减1
redis.call('incrby',stockKey,-1)

-- 下单保存用户,用户id存入set
redis.call('sadd',orderKey,userId)

-- 发送消息到队列中
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
