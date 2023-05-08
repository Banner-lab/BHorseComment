package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
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
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService iUserService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if(blog == null) {
            return Result.fail("该博客不存在");
        }
        Long userId = blog.getUserId();
        User user = iUserService.getById(userId);
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(userId));
        if(score != null){//已点赞，再次点击取消点赞
            boolean removed = update().setSql("liked = liked -1").eq("id", id).update();
            if(removed){
                stringRedisTemplate.opsForZSet().remove(key, String.valueOf(userId));
            }
        }else{
            boolean plused = update().setSql("liked = liked + 1").eq("id", id).update();
            if(plused){
                stringRedisTemplate.opsForZSet().add(key, String.valueOf(userId),System.currentTimeMillis());
            }
        }
        return Result.ok();
    }

    @Override
    public void isBlogLiked(Blog blog) {
        if(UserHolder.getUser() == null){
            return ;
        }
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(userId));
        blog.setIsLike(score != null);
    }

    @Override
    public Result queryBlogsLike(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY+id;
        Set<String> likes = stringRedisTemplate.opsForZSet().range(key, 0, 2);

        if(likes == null || likes.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = likes.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> top3 = new ArrayList<>();
        ids.forEach(i->{
            User user = iUserService.getById(i);
            UserDTO userDTO = new UserDTO();
            userDTO.setId(i);
            userDTO.setIcon(user.getIcon());
            userDTO.setNickName(user.getNickName());
            top3.add(userDTO);
        });
        return Result.ok(top3);
    }
}
