package com.smartinterview.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.jwt.JWTUtil;
import com.smartinterview.common.constants.RedisConstants;
import com.smartinterview.common.util.JwtProperties;
import com.smartinterview.common.util.JwtUtil;
import com.smartinterview.common.util.UserHolder;
import com.smartinterview.dto.UserDTO;
import com.smartinterview.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class RefreshInterceptor implements HandlerInterceptor {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private JwtProperties jwtProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //System.out.println("================");

        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 2. 获取 Token（尝试大写和小写，增强兼容性）
        String token = request.getHeader("Authorization");
        if (StrUtil.isBlank(token)) {
            token = request.getHeader("authorization");
        }
        //SSE: 2. 如果 Header 没有，尝试从 URL 参数获取 (专门给 SSE 用)
        if (StrUtil.isBlank(token)) {
            token = request.getParameter("token");
        }

        if(StrUtil.isBlank(token)){
            return true;
        }
        try {
            JwtUtil.parseJWT(jwtProperties.getUserSecretkey(),token);
            Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER + token);
            if(userMap.isEmpty()||userMap.size()==0){
                return true;
            }
            UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
            UserHolder.save(userDTO);
            stringRedisTemplate.expire(RedisConstants.LOGIN_USER+token,RedisConstants.LOGIN_TOKEN_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("jwt解析失败：{}",e.getMessage());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.remove();
    }
}
