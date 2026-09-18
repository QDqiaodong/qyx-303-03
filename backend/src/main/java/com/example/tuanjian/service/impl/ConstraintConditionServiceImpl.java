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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        return constraintRepository.save(constraint);
    }

    @Override
    @Transactional
    public ConstraintCondition updateTemplate(Long id, ConstraintRequest request) {
        ConstraintCondition constraint = getActiveTemplateForUpdate(id);
        constraint.setTemplateName(request.getTemplateName());
        constraint.setBudgetLimit(request.getBudgetLimit());
        constraint.setMaxDurationDays(request.getMaxDurationDays());
        constraint.setParticipantCount(request.getParticipantCount());
        constraint.setRequiredActivities(request.getRequiredActivities());
        return constraintRepository.save(constraint);
    }

    @Override
    @Transactional
    public void deleteTemplate(Long id) {
        ConstraintCondition constraint = constraintRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("约束模板不存在: " + id));
        if (!ConstraintCondition.STATUS_ACTIVE.equals(constraint.getStatus())) {
            // 并发删除落败方必须看到“模板已不在”，而不是还能拿它当旧依据
            throw new NoSuchElementException("约束模板不存在: " + id);
        }
        constraint.setStatus(ConstraintCondition.STATUS_DELETED);
        constraintRepository.saveAndFlush(constraint);
        evictTemplateCacheAfterCommit(id);
    }

    @Override
    @Transactional(readOnly = true)
    public ConstraintCondition getTemplateById(Long id) {
        return getActiveTemplate(id);
    }

    @Override
    public ConstraintCondition getActiveTemplateForUpdate(Long id) {
        return constraintRepository.findByIdAndStatusForUpdate(id, ConstraintCondition.STATUS_ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("约束模板不存在或已删除: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConstraintCondition> getAllTemplates() {
        return constraintRepository.findByStatusOrderByCreatedAtDesc(ConstraintCondition.STATUS_ACTIVE);
    }

    private ConstraintCondition getActiveTemplate(Long id) {
        return constraintRepository.findByIdAndStatus(id, ConstraintCondition.STATUS_ACTIVE)
                .orElseThrow(() -> new NoSuchElementException("约束模板不存在: " + id));
    }

    private void evictTemplateCacheAfterCommit(Long id) {
        Runnable evict = () -> {
            try {
                deleteFromRedis("template:" + id);
                deleteFromRedis("current");
            } catch (Exception e) {
                log.warn("模板 {} 已删除，但旧缓存清理失败，入口仍会按数据库状态拒绝", id, e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict.run();
                }
            });
        } else {
            evict.run();
        }
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