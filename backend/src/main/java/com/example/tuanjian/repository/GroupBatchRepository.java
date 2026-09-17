package com.example.tuanjian.repository;

import com.example.tuanjian.entity.GroupBatch;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GroupBatchRepository extends JpaRepository<GroupBatch, Long> {

    List<GroupBatch> findAllByOrderByCreatedAtDesc();

    List<GroupBatch> findByStatusOrderByCreatedAtDesc(String status);

    List<GroupBatch> findByPlanIdAndStatusOrderByCreatedAtDesc(Long planId, String status);

    /**
     * 同一场地同一天是否已有生效批次。唯一索引 uk_group_batch_active_slot
     * 是最终兜底，两个并发落批次时只有一个能插入成功。
     */
    boolean existsByActiveKey(String activeKey);

    /**
     * 行级悲观锁：两道签字、交场回执生成必须串行化，
     * 两个复核人几乎同时签也只能生成一笔回执。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select b from GroupBatch b where b.id = :id")
    Optional<GroupBatch> findByIdForUpdate(Long id);

}
