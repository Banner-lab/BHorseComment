package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService iUserService;
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:"+userId;
        if(isFollow){
            //关注
            Follow follow = new Follow();
            follow.setFollowUserId(followUserId);
            follow.setUserId(userId);
            boolean isSaved = save(follow);
            if(isSaved){

                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else{
            //取关
            LambdaQueryWrapper<Follow> followLambdaQueryWrapper = new LambdaQueryWrapper<>();
            followLambdaQueryWrapper.eq(Follow::getFollowUserId, followUserId);
            followLambdaQueryWrapper.eq(Follow::getUserId, userId);
            remove(followLambdaQueryWrapper);
            stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();

        return Result.ok(count > 0);
    }

    @Override
    public Result followCommon(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        String key1  = "follows:"+userId;
        String key2 = "follows:"+followUserId;

        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> userDTOS = new ArrayList<>();
        ids.forEach(u->{
            User user = iUserService.getById(u);
            UserDTO userDTO = new UserDTO();
            userDTO.setId(user.getId());
            userDTO.setIcon(user.getIcon());
            userDTO.setNickName(user.getNickName());
            userDTOS.add(userDTO);
        });
        return Result.ok(userDTOS);
    }
}
