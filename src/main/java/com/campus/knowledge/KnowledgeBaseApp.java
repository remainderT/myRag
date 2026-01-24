package com.campus.knowledge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 校园智能知识库系统启动类
 * 
 * @version 2.0
 */
@SpringBootApplication
public class KnowledgeBaseApp {

    private static ConfigurableApplicationContext context;

    public static void main(String[] args) {
        context = SpringApplication.run(KnowledgeBaseApp.class, args);
        displayStartupInfo();
    }
    /**
     * 显示启动信息
     */
    private static void displayStartupInfo() {
        String banner = """
                ╔═══════════════════════════════════════╗
                ║   校园智能知识库系统已启动             ║
                ╚═══════════════════════════════════════╝
                """;
        System.out.println(banner);
    }

}
