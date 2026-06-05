package com.example.aispringvideo;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@MapperScan("com.example.aispringvideo.mapper")
@EnableAsync
public class AispringVideoApplication {

    public static void main(String[] args) {
        SpringApplication.run(AispringVideoApplication.class, args);
    }

}
