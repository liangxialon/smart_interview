package com.smartinterview.interceptor;

import com.smartinterview.annocation.AdminRequired;
import com.smartinterview.common.util.UserHolder;
import com.smartinterview.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

@Component
public class AdminInterceptor implements HandlerInterceptor {

    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        //System.out.println("========");
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        //handler包含拦截到的controller路径方法，bean对象，还用方法注解等信息
        if(!(handler instanceof HandlerMethod)){
            //instanceof判断一个对象是否是另一个类的子类或实现类或bean实例
            //如果拦截的不是controller里的方法，直接放行
            return true;
        }
        //强转为handlerMethod对象获取拦截到的controller路径方法信息
        HandlerMethod method=(HandlerMethod) handler;
        //没有注解直接放行
        if(!method.hasMethodAnnotation(AdminRequired.class))return true;
        UserDTO userDTO= UserHolder.getUser();
        //有注解判断用户角色，管理员才能上传题库
        if(userDTO==null||!Integer.valueOf(1).equals(userDTO.getRole())){
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"msg\":\"无权限，仅管理员可操作\"}");
            return false;
        }
        return true;
    }
}
