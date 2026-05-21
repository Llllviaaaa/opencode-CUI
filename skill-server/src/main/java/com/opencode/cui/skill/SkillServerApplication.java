package com.opencode.cui.skill;

import com.opencode.cui.skill.config.InternalAuthProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(InternalAuthProperties.class)
@MapperScan("com.opencode.cui.skill.repository")
public class SkillServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillServerApplication.class, args);
    }
}
