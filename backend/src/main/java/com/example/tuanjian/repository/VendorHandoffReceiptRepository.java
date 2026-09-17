package com.example.tuanjian.repository;

import com.example.tuanjian.entity.VendorHandoffReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VendorHandoffReceiptRepository extends JpaRepository<VendorHandoffReceipt, Long> {

    /** 每个批次至多一笔回执；前端/台账据此判断场地是否已交给供应商 */
    Optional<VendorHandoffReceipt> findByBatchId(Long batchId);

    boolean existsByBatchId(Long batchId);

}
