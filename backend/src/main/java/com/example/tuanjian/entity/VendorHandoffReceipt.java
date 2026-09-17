package com.example.tuanjian.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 交场回执：现场对接人到场签字 + 科室复核人复核签字两道都齐了，才允许生成一笔。
 * 一个批次至多一笔回执（batch_id 唯一索引兜底并发）：
 * 两个复核人几乎同时签，系统也只留一笔回执，后到者看到的是"场地已经交给供应商"。
 * 回执生成即代表场地已交给供应商，与批次 handedOff、预算池数字保持同一口径。
 */
@Entity
@Table(name = "vendor_handoff_receipt", uniqueConstraints = {
        @UniqueConstraint(name = "uk_handoff_receipt_batch", columnNames = {"batch_id"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VendorHandoffReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 一个批次只能有一笔交场回执 */
    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "batch_no", nullable = false, length = 40)
    private String batchNo;

    @Column(name = "venue", nullable = false, length = 200)
    private String venue;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    @Column(name = "contact_person", nullable = false, length = 60)
    private String contactPerson;

    @Column(name = "contact_signed_at", nullable = false)
    private LocalDateTime contactSignedAt;

    @Column(name = "review_person", nullable = false, length = 60)
    private String reviewPerson;

    @Column(name = "review_signed_at", nullable = false)
    private LocalDateTime reviewSignedAt;

    /** 回执生成时间 = 场地正式交给供应商的时间 */
    @Column(name = "handed_off_at", nullable = false, updatable = false)
    private LocalDateTime handedOffAt;

    @PrePersist
    protected void onCreate() {
        if (handedOffAt == null) {
            handedOffAt = LocalDateTime.now();
        }
    }

}
