package com.opencode.cui.skill.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ImTokenAuthInterceptor imTokenAuthInterceptor;
    private final MdcRequestInterceptor mdcRequestInterceptor;

    public WebMvcConfig(ImTokenAuthInterceptor imTokenAuthInterceptor,
                        MdcRequestInterceptor mdcRequestInterceptor) {
        this.imTokenAuthInterceptor = imTokenAuthInterceptor;
        this.mdcRequestInterceptor = mdcRequestInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // MDC 拦截器在认证之前执行，确保认证日志也带有 traceId
        registry.addInterceptor(mdcRequestInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(imTokenAuthInterceptor)
                .addPathPatterns("/api/inbound/**");
    }
}
