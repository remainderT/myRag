package org.buaa.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
@MapperScan("org.buaa.rag.dao.mapper")
public class RagApp {

    public static void main(String[] args) {
         SpringApplication.run(RagApp.class, args);
    }
}
