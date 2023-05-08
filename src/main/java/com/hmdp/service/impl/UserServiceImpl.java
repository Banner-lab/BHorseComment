package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        boolean phoneInvalid = RegexUtils.isPhoneInvalid(phone);
        if(phoneInvalid){
            return Result.fail("手机号格式不正确");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);

        //将验证码保存在session中
        //session.setAttribute(phone,code);

        //将验证码存储在redis数据库中,并设置验证码有限期为2分钟
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY+phone,code,RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //发送短信验证码
        log.debug("发送短信验证码成功:"+code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式错误");
        }
        //校验验证码,从redis数据库中获取验证码
        String phone = loginForm.getPhone();
        String cacheCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();//前端提交的验证码
        // 校验错误
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        // 根据手机号从数据库查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
        if(user == null){
            user = createUserByPhone(loginForm.getPhone());
        }
        //生成随机token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //保存用户信息到redis,user对象转为map转存,注意stringRedisTemplate要求键值都为string类
        Map<String, Object> userDtoMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((k,v)-> v.toString()));

        stringRedisTemplate.opsForHash().putAll(RedisConstants.LOGIN_USER_KEY+token, userDtoMap);
        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+token,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //返回登录成功
        return Result.ok(token);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        log.debug("token:{}"+token);
        String key = RedisConstants.LOGIN_USER_KEY+token;
        log.debug("key:{}"+key);
        //stringRedisTemplate.opsForHash().entries(key).clear();
        stringRedisTemplate.opsForHash().delete(key, "nickName","icon","id");
        return Result.ok("退出成功");
    }

    private User createUserByPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        String randName = SystemConstants.USER_NICK_NAME_PREFIX+RandomUtil.randomString(10);
        user.setNickName(randName);
        // 保存用户到数据库
        save(user);
        return user;
    }
}
