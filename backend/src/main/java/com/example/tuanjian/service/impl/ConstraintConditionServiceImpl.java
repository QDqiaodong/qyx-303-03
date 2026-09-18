package com.example.tuanjian.service.impl;

import com.example.tuanjian.dto.request.ConstraintRequest;
import com.example.tuanjian.entity.ConstraintCondition;
import com.example.tuanjian.repository.ConstraintConditionRepository;
import com.example.tuanjian.service.ConstraintConditionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConstraintConditionServiceImpl implements ConstraintConditionService {

    private final ConstraintConditionRepository constraintRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String REDIS_KEY_PREFIX = "tuanjian:constraint:";
    private static final int REDIS_EXPIRE_HOURS = 24;

    @Override
    @Transactional
    public ConstraintCondition createTemplate(ConstraintRequest request) {
        ConstraintCondition constraint = ConstraintCondition.builder()
                .templateName(request.getTemplateName())
                .budgetLimit(request.getBudgetLimit())
                .maxDurationDays(request.getMaxDurationDays())
                .participantCount(request.getParticipantCount())
                .requiredActivities(request.getRequiredActivities())
                .status(ConstraintCondition.STATUS_ACTIVE)
                .build();
        ConstraintCondition saved = constraintRepository.save(constraint);
        request.setTemplateId(saved.getId());
        saveToRedis("template:" + saved.getId(), request);
        return saved;
    }

    @Override
    @Transactional
    public ConstraintCondition updateTemplate(Long id, ConstraintRequest request) {
        ConstraintCondition constraint = constraintRepository
                .findByIdAndStatus(id, ConstraintCondition.STATUS_ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("约束模板不存在: " + id));
        constraint.setTemplateName(request.getTemplateName());
        constraint.setBudgetLimit(request.getBudgetLimit());
        constraint.setMaxDurationDays(request.getMaxDurationDays());
        constraint.setParticipantCount(request.getParticipantCount());
        constraint.setRequiredActivities(request.getRequiredActivities());
        ConstraintCondition saved = constraintRepository.save(constraint);
        request.setTemplateId(id);
        saveToRedis("template:" + saved.getId(), request);
        return saved;
    }

    @Override
    @Transactional
    public void deleteTemplate(Long id) {
        // 条件更新是一条原子 SQL：只有仍启用的模板能被删。并发删除只有一个事务影响 1 行，
        // 后到者看到 404；更新失败则状态不变，不会出现列表隐藏但对比仍认旧编号之外的中间态。
        int updated = constraintRepository.markDeletedIfActive(
                id,
                ConstraintCondition.STATUS_ACTIVE,
                ConstraintCondition.STATUS_DELETED,
                LocalDateTime.now());
        if (updated == 0) {
            throw new NoSuchElementException("约束模板不存在: " + id);
        }
        deleteFromRedis("template:" + id);
    }

    @Override
    public ConstraintCondition getTemplateById(Long id) {
        return constraintRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("约束模板不存在: " + id));
    }

    @Override
    public ConstraintCondition getActiveTemplateById(Long id) {
        return constraintRepository.findByIdAndStatus(id, ConstraintCondition.STATUS_ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("约束模板不存在或已删除: " + id));
    }

    @Override
    @Transactional
    public ConstraintRequest getActiveConstraintRequest(Long id) {
        ConstraintCondition template = constraintRepository
                .findByIdAndStatusForUpdate(id, ConstraintCondition.STATUS_ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("约束模板不存在或已删除: " + id));
        return ConstraintRequest.builder()
                .templateId(template.getId())
                .templateName(template.getTemplateName())
                .budgetLimit(template.getBudgetLimit())
                .maxDurationDays(template.getMaxDurationDays())
                .participantCount(template.getParticipantCount())
                .requiredActivities(template.getRequiredActivities())
                .build();
    }

    @Override
    public List<ConstraintCondition> getAllTemplates() {
        return constraintRepository.findByStatusOrderByCreatedAtDesc(ConstraintCondition.STATUS_ACTIVE);
    }

    @Override
    public void saveToRedis(String key, ConstraintRequest constraint) {
        String redisKey = REDIS_KEY_PREFIX + key;
        try {
            String json = objectMapper.writeValueAsString(constraint);
            redisTemplate.opsForValue().set(redisKey, json, REDIS_EXPIRE_HOURS, TimeUnit.HOURS);
            log.info("Saved constraint to Redis: {}", redisKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize constraint for Redis", e);
        }
    }

    @Override
    public ConstraintRequest getFromRedis(String key) {
        String redisKey = REDIS_KEY_PREFIX + key;
        String json = redisTemplate.opsForValue().get(redisKey);
        if (json != null) {
            try {
                return objectMapper.readValue(json, ConstraintRequest.class);
            } catch (JsonProcessingException e) {
                log.error("Failed to deserialize constraint from Redis", e);
            }
        }
        return null;
    }

    @Override
    public void deleteFromRedis(String key) {
        String redisKey = REDIS_KEY_PREFIX + key;
        redisTemplate.delete(redisKey);
        log.info("Deleted constraint from Redis: {}", redisKey);
    }

}