package org.buaa.rag.service;

import org.buaa.rag.dto.MetadataFilter;
import org.buaa.rag.dto.QueryPlan;

/**
 * 查询分析服务接口
 * 负责查询改写/HyDE生成与元数据路由解析
 */
public interface QueryAnalysisService {

    /**
     * 创建查询计划
     */
    QueryPlan createPlan(String userQuery);

    /**
     * 解析查询过滤条件
     */
    MetadataFilter resolveFilter(String query);
}
