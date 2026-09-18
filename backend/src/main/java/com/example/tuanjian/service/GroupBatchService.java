package com.example.tuanjian.service;

import com.example.tuanjian.dto.request.GroupBatchLandingRequest;
import com.example.tuanjian.entity.GroupBatch;
import com.example.tuanjian.entity.VendorHandoffReceipt;

import java.math.BigDecimal;
import java.util.List;

public interface GroupBatchService {

    /**
     * 落地成团：模板必须仍启用，服务端在本事务中锁定模板并按模板现值校验方案
     * （天数/必备活动 + 方案人数范围 + 池子余额）。写批次台账并从预算池扣钱，
     * 两件事在同一个事务里同时做成。模板已删、同日同场地已有生效批次、或并发抢占时，
     * 抛异常且预算不扣。
     */
    GroupBatch land(GroupBatchLandingRequest request, String templateBudgetNote);

    List<GroupBatch> listBatches(String status);

    GroupBatch getBatch(Long id);

    /**
     * 方案人均费用被改动后调用：
     * <ul>
     *   <li>尚未交场（两道签字未齐）的生效批次：按落地当时成团人数用新费用重算，
     *       对不上原扣额的置失效、钱退回池子（老规则）。</li>
     *   <li>已交场（回执已生成、供应商已备场）的批次：不作废、不退款、出行日锁死，
     *       只追加一条金额为 0 的 RECONCILE 对账说明（财务口径）。</li>
     * </ul>
     * 返回被置失效的批次数。
     */
    int invalidateBatchesByPlanCostChange(Long planId, BigDecimal newCostPerPerson);

    /**
     * 第一道签字：现场对接人在批次上留到场记录。
     * 复核人还没签之前，对接人自己再点也不会交场；已交场的批次拒绝重复签。
     */
    GroupBatch contactArrive(Long id, String signerName);

    /**
     * 第二道签字：科室复核人复核。两道签字齐了才生成唯一一笔交场回执并放行供应商。
     * 并发下只有一个复核人能成功，后到者收到 409 并看到场地已交给供应商。
     */
    GroupBatch reviewSign(Long id, String signerName);

    /** 查交场回执；不存在返回 null（两道签字未齐 = 场地还没交给供应商） */
    VendorHandoffReceipt getReceipt(Long batchId);

    /**
     * 对接场地供应商：只有两道签字齐、回执已生成（handedOff）的批次允许；
     * 只签了到场记录、已失效批次一律禁止。
     */
    GroupBatch contactSupplier(Long id);

    static String activeKey(java.time.LocalDate travelDate, String venue) {
        return travelDate.toString() + ':' + venue;
    }

}
