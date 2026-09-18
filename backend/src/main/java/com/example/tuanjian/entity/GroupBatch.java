package com.example.tuanjian.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 落地批次台账：从当次对比结果里挑一份当时合规的方案落成一条批次。
 * 同一天同一场地只允许一条仍生效（ACTIVE）的批次，
 * activeKey 仅生效行有值（travelDate + ':' + venue），靠唯一索引兜底并发。
 * 失效（INVALID）后钱已退回池子，不能再对接场地供应商。
 *
 * 供应商交场走两道签字：现场对接人先留到场记录（contactSign*），
 * 科室复核人再签（reviewSign*）。两道签字齐了才生成交场回执（VendorHandoffReceipt）
 * 并把 handedOff 置 true，场地才算交给供应商；只签一道不算交场。
 * handedOff 之后：池子的钱不动、出行日锁死、改人均只追加一条对账说明。
 */
@Entity
@Table(name = "group_batch", uniqueConstraints = {
        @UniqueConstraint(name = "uk_group_batch_active_slot",
                columnNames = {"active_key"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupBatch {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_INVALID = "INVALID";

    /** 失效原因：方案人均费用变化，按落地人数重算后对不上当初扣下的额度 */
    public static final String REASON_COST_CHANGED = "PLAN_COST_CHANGED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, unique = true, length = 40)
    private String batchNo;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    @Column(name = "plan_name", nullable = false, length = 100)
    private String planName;

    /**
     * 落地时引用的模板编号和名称快照。之后模板删除只禁止新对比/新落地，
     * 不回滚这条已落地批次的预算和场地占用。
     */
    @Column(name = "constraint_template_id")
    private Long constraintTemplateId;

    @Column(name = "constraint_template_name", length = 100)
    private String constraintTemplateName;

    @Column(name = "venue", nullable = false, length = 200)
    private String venue;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    @Column(name = "group_size", nullable = false)
    private Integer groupSize;

    /** 落地当时方案的人均费用，之后方案费用变化不会改这里 */
    @Column(name = "locked_cost_per_person", nullable = false, precision = 10, scale = 2)
    private BigDecimal lockedCostPerPerson;

    /** 从预算池实际扣下的金额 = lockedCostPerPerson * groupSize */
    @Column(name = "locked_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal lockedAmount;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "invalid_reason", length = 40)
    private String invalidReason;

    /** 生效行 = travelDate + ':' + venue，失效行置 null */
    @Column(name = "active_key", length = 230)
    private String activeKey;

    /** 第一道签字：现场对接人已留到场记录 */
    @Column(name = "contact_signed", nullable = false)
    @Builder.Default
    private boolean contactSigned = false;

    @Column(name = "contact_person", length = 60)
    private String contactPerson;

    @Column(name = "contact_signed_at")
    private LocalDateTime contactSignedAt;

    /** 第二道签字：科室复核人已复核 */
    @Column(name = "review_signed", nullable = false)
    @Builder.Default
    private boolean reviewSigned = false;

    @Column(name = "review_person", length = 60)
    private String reviewPerson;

    @Column(name = "review_signed_at")
    private LocalDateTime reviewSignedAt;

    /**
     * 两道签字齐、交场回执已生成后置 true：场地已交给供应商。
     * 此后池子的钱不动、出行日锁死、方案改人均只追加对账说明。
     */
    @Column(name = "handed_off", nullable = false)
    @Builder.Default
    private boolean handedOff = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

}
