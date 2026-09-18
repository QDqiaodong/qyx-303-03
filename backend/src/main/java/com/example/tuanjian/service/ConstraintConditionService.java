package com.example.tuanjian.service;

import com.example.tuanjian.dto.request.ConstraintRequest;
import com.example.tuanjian.entity.ConstraintCondition;

import java.util.List;

public interface ConstraintConditionService {

    ConstraintCondition createTemplate(ConstraintRequest request);

    ConstraintCondition updateTemplate(Long id, ConstraintRequest request);

    void deleteTemplate(Long id);

    ConstraintCondition getTemplateById(Long id);

    /** 对比/落地事务内使用：加锁读取启用模板；已删除或不存在直接报错，和删除事务互斥 */
    ConstraintCondition getActiveTemplateForUpdate(Long id);

    List<ConstraintCondition> getAllTemplates();

    void saveToRedis(String key, ConstraintRequest constraint);

    ConstraintRequest getFromRedis(String key);

    void deleteFromRedis(String key);

}