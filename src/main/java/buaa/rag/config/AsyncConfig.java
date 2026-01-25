package buaa.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务执行器配置。
 * 用于文档解析与向量 索引的后台任务，避免阻塞上传请求。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 文档摄取任务线程池。
     *
     * @return 异步执行器
     */
    @Bean(name = "documentIngestionExecutor")
    public Executor documentIngestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("document-ingestion-");
        executor.initialize();
        return executor;
    }
}
