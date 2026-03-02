package com.yourapp.skill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.yourapp.skill.repository")
public class SkillServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkillServerApplication.class, args);
    }
}
