package com.example.tuanjian.repository;

import com.example.tuanjian.entity.ConstraintCondition;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConstraintConditionRepository extends JpaRepository<ConstraintCondition, Long> {

    List<ConstraintCondition> findByTemplateNameContaining(String templateName);

    List<ConstraintCondition> findByStatusOrderByCreatedAtDesc(String status);

    Optional<ConstraintCondition> findByIdAndStatus(Long id, String status);

    /** 对比/落地与删除互斥：先拿到行锁才允许继续使用模板。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select c from ConstraintCondition c where c.id = :id and c.status = :status")
    Optional<ConstraintCondition> findByIdAndStatusForUpdate(@Param("id") Long id, @Param("status") String status);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ConstraintCondition c set c.status = :deletedStatus, c.updatedAt = :updatedAt "
            + "where c.id = :id and c.status = :activeStatus")
    int markDeletedIfActive(@Param("id") Long id,
                            @Param("activeStatus") String activeStatus,
                            @Param("deletedStatus") String deletedStatus,
                            @Param("updatedAt") LocalDateTime updatedAt);

}
