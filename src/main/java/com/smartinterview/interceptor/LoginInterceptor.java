package com.smartinterview.interceptor;

import com.smartinterview.common.result.Result;
import com.smartinterview.common.util.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

       // System.out.println("================");
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }


        if(UserHolder.getUser()==null){
            response.setStatus(401);
            return false;
        }
        return true;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {

    }
}
