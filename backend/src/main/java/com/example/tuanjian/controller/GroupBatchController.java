package com.example.tuanjian.controller;

import com.example.tuanjian.dto.request.GroupBatchLandingRequest;
import com.example.tuanjian.dto.request.HandoffSignRequest;
import com.example.tuanjian.entity.GroupBatch;
import com.example.tuanjian.entity.VendorHandoffReceipt;
import com.example.tuanjian.service.GroupBatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/batches")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class GroupBatchController {

    private final GroupBatchService groupBatchService;

    /**
     * 落地成团：从当次对比里挑一份方案，写出行日期和成团人数，
     * 批次台账和预算扣款在同一事务内同时做成。
     */
    @PostMapping("/land")
    public ResponseEntity<GroupBatch> land(@Valid @RequestBody GroupBatchLandingRequest request) {
        GroupBatch batch = groupBatchService.land(request, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(batch);
    }

    /** 批次台账：可按状态过滤 ACTIVE / INVALID */
    @GetMapping
    public ResponseEntity<List<GroupBatch>> listBatches(
            @RequestParam(value = "status", required = false) String status) {
        return ResponseEntity.ok(groupBatchService.listBatches(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<GroupBatch> getBatch(@PathVariable Long id) {
        return ResponseEntity.ok(groupBatchService.getBatch(id));
    }

    /** 该批次的交场回执；两道签字未齐时返回 404（场地还没交给供应商） */
    @GetMapping("/{id}/receipt")
    public ResponseEntity<VendorHandoffReceipt> getReceipt(@PathVariable Long id) {
        VendorHandoffReceipt receipt = groupBatchService.getReceipt(id);
        if (receipt == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(receipt);
    }

    /**
     * 第一道签字：现场对接人在批次上留到场记录。
     * 复核人没签之前，对接人自己再点一次不视为完成（返回 409）。
     */
    @PostMapping("/{id}/contact-arrive")
    public ResponseEntity<GroupBatch> contactArrive(@PathVariable Long id,
                                                    @Valid @RequestBody HandoffSignRequest request) {
        return ResponseEntity.ok(groupBatchService.contactArrive(id, request.getSignerName()));
    }

    /**
     * 第二道签字：科室复核人复核。两道签字齐了才生成唯一一笔交场回执、放行供应商；
     * 并发复核只有一笔回执，后到者收到 409 并看到场地已交给供应商。
     */
    @PostMapping("/{id}/review-sign")
    public ResponseEntity<GroupBatch> reviewSign(@PathVariable Long id,
                                                 @Valid @RequestBody HandoffSignRequest request) {
        return ResponseEntity.ok(groupBatchService.reviewSign(id, request.getSignerName()));
    }

    /**
     * 对接场地供应商：只有两道签字齐、回执已生成的生效批次允许；
     * 只签了到场记录、或因费用变化已失效（钱已退回）的批次返回冲突错误。
     */
    @PostMapping("/{id}/contact-supplier")
    public ResponseEntity<GroupBatch> contactSupplier(@PathVariable Long id) {
        return ResponseEntity.ok(groupBatchService.contactSupplier(id));
    }

}
