package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryList() {
        String key = RedisConstants.SHOP_TYPE_KEY;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        if(!StrUtil.isBlank(jsonStr)){
            List<ShopType> list = JSONUtil.toList(jsonStr, ShopType.class);
            return Result.ok(list);
        }
        List<ShopType> list = query().orderByAsc("sort").list();
        if(list == null){
            return Result.fail("查询失败");
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(list));
        return Result.ok(list);
    }
}
