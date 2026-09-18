package com.example.tuanjian.service;

import com.example.tuanjian.dto.request.ConstraintRequest;
import com.example.tuanjian.entity.ConstraintCondition;

import java.util.List;

public interface ConstraintConditionService {

    ConstraintCondition createTemplate(ConstraintRequest request);

    ConstraintCondition updateTemplate(Long id, ConstraintRequest request);

    void deleteTemplate(Long id);

    ConstraintCondition getTemplateById(Long id);

    /** 对比和落地入口只允许取得仍启用的模板；已删编号直接 404。 */
    ConstraintCondition getActiveTemplateById(Long id);

    /** 以库里仍启用的模板生成对比/落地条件，调用方不得信任前端传来的旧条件副本。 */
    ConstraintRequest getActiveConstraintRequest(Long id);

    List<ConstraintCondition> getAllTemplates();

    void saveToRedis(String key, ConstraintRequest constraint);

    ConstraintRequest getFromRedis(String key);

    void deleteFromRedis(String key);

}