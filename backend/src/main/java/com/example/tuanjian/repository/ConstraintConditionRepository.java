package com.example.tuanjian.repository;

import com.example.tuanjian.entity.ConstraintCondition;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConstraintConditionRepository extends JpaRepository<ConstraintCondition, Long> {

    List<ConstraintCondition> findByTemplateNameContainingAndStatus(String templateName, String status);

    List<ConstraintCondition> findByStatusOrderByCreatedAtDesc(String status);

    Optional<ConstraintCondition> findByIdAndStatus(Long id, String status);

    /** 对比/落地事务内加写锁：模板删除与入口校验互斥，杜绝旧编号继续放行 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConstraintCondition c where c.id = :id and c.status = :status")
    Optional<ConstraintCondition> findByIdAndStatusForUpdate(@Param("id") Long id,
                                                             @Param("status") String status);

    /** 删除前先锁住同一行（不区分状态），让并发删除/使用在数据库层串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ConstraintCondition c where c.id = :id")
    Optional<ConstraintCondition> findByIdForUpdate(@Param("id") Long id);

}
