package com.example.tuanjian.service;

import com.example.tuanjian.dto.response.BudgetPoolView;
import com.example.tuanjian.entity.BudgetTransaction;

import java.math.BigDecimal;
import java.util.List;

public interface BudgetService {

    /** 应用启动时确保预算池单例存在，总额取配置 tuanjian.budget.initial-total */
    void initializePoolIfAbsent();

    BudgetPoolView getPool();

    /**
     * 占用预算（落地成团）。必须在调用方事务内执行：
     * 行锁串行化，余额不足抛 {@link com.example.tuanjian.exception.BudgetInsufficientException}，
     * 同时写 HOLD 流水。批次台账和这笔扣款要么一起成，要么一起回滚。
     */
    void hold(Long batchId, Long planId, BigDecimal amount, String remark);

    /**
     * 退回预算（批次失效）。同样在调用方事务内，写 REFUND 流水。
     */
    void refund(Long batchId, Long planId, BigDecimal amount, String remark);

    /**
     * 调整池子总额（行政充值/追加预算），写 ADJUST 流水，不能把已占用的部分削掉。
     */
    BudgetPoolView adjustTotal(BigDecimal newTotal, String remark);

    /**
     * 交场回执齐了之后方案人均被改：财务口径只追加一条对账说明（RECONCILE，金额恒为 0），
     * 池子已占用/可占用余额一律不动，也不作废批次。供应商已按回执备场，钱不退、出行日锁死。
     */
    void reconcileNote(Long batchId, Long planId, String remark);

    List<BudgetTransaction> getTransactions();

}
