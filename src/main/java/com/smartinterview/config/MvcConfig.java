package com.smartinterview.config;
import com.smartinterview.interceptor.AdminInterceptor;
import com.smartinterview.interceptor.LoginInterceptor;
import com.smartinterview.interceptor.RefreshInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration

public class MvcConfig implements WebMvcConfigurer {

    @Autowired
    private RefreshInterceptor refreshInterceptor;

    @Autowired
    private LoginInterceptor loginInterceptor;
    @Autowired
    private AdminInterceptor adminInterceptor;

    // 统一定义需要放行的接口文档路径
    private static final String[] SWAGGER_EXCLUDE_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/webjars/**",
            "/doc.html"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // RefreshInterceptor 拦截所有请求，但也建议排除 Swagger 路径，避免不必要的 Redis 交互
        registry.addInterceptor(refreshInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(SWAGGER_EXCLUDE_PATHS)
                .order(0);

        // LoginInterceptor 排除无需登录的接口和 Swagger 路径
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/**") // 明确指定拦截所有，再排除
                .excludePathPatterns(
                        // 用户相关接口
                        "/user/code/login",
                        "/user/code",
                        "/user/password/login",
                        "/user/register"
                )
                .excludePathPatterns(SWAGGER_EXCLUDE_PATHS) // 追加放行文档路径
                .order(1);
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(SWAGGER_EXCLUDE_PATHS)
                .order(2);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*") // 允许任何来源，最省事
               // .allowedMethods("*")
                .allowedMethods("GET","POST","PUT","DELETE","OPTIONS","PATCH")
                .allowedHeaders("*")
                .maxAge(300000)
                .allowCredentials(true);
    }



    }
