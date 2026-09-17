package com.example.tuanjian.service.impl;

import com.example.tuanjian.dto.request.GroupBatchLandingRequest;
import com.example.tuanjian.entity.GroupBatch;
import com.example.tuanjian.entity.TeamBuildingPlan;
import com.example.tuanjian.entity.VendorHandoffReceipt;
import com.example.tuanjian.exception.BusinessConflictException;
import com.example.tuanjian.repository.GroupBatchRepository;
import com.example.tuanjian.repository.TeamBuildingPlanRepository;
import com.example.tuanjian.repository.VendorHandoffReceiptRepository;
import com.example.tuanjian.service.BudgetService;
import com.example.tuanjian.service.GroupBatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
@Slf4j
public class GroupBatchServiceImpl implements GroupBatchService {

    private final GroupBatchRepository batchRepository;
    private final TeamBuildingPlanRepository planRepository;
    private final VendorHandoffReceiptRepository receiptRepository;
    private final BudgetService budgetService;

    private static final DateTimeFormatter BATCH_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Override
    @Transactional
    public GroupBatch land(GroupBatchLandingRequest request, String templateBudgetNote) {
        TeamBuildingPlan plan = planRepository.findById(request.getPlanId())
                .orElseThrow(() -> new NoSuchElementException("方案不存在: " + request.getPlanId()));

        int groupSize = request.getGroupSize();
        String activeKey = GroupBatchService.activeKey(request.getTravelDate(), plan.getVenue());

        // 先做规则校验，给出可读错误；并发情况下再由唯一索引兜底（DataIntegrityViolationException）
        if (batchRepository.existsByActiveKey(activeKey)) {
            throw new BusinessConflictException(
                    String.format("场地【%s】在 %s 已有生效的落地批次，不能重复占位",
                            plan.getVenue(), request.getTravelDate()));
        }
        if (groupSize < plan.getMinParticipants() || groupSize > plan.getMaxParticipants()) {
            throw new BusinessConflictException(
                    String.format("成团人数 %d 不在方案支持范围 %d-%d 人内",
                            groupSize, plan.getMinParticipants(), plan.getMaxParticipants()));
        }
        if (plan.getDurationDays() > request.getMaxDurationDays()) {
            throw new BusinessConflictException(
                    String.format("方案时长 %d 天超过当次对比的最大出行天数 %d 天",
                            plan.getDurationDays(), request.getMaxDurationDays()));
        }
        if (!containsRequiredActivities(plan.getSuitableActivities(), request.getRequiredActivities())) {
            throw new BusinessConflictException("方案不满足当次对比的必备活动要求: "
                    + request.getRequiredActivities());
        }

        BigDecimal lockedCostPerPerson = plan.getCostPerPerson();
        BigDecimal lockedAmount = lockedCostPerPerson.multiply(BigDecimal.valueOf(groupSize));

        String batchNo = "TB" + LocalDateTime.now().format(BATCH_NO_FORMAT)
                + String.format("%04d", java.util.concurrent.ThreadLocalRandom.current().nextInt(10000));

        GroupBatch batch = GroupBatch.builder()
                .batchNo(batchNo)
                .planId(plan.getId())
                .planName(plan.getPlanName())
                .venue(plan.getVenue())
                .travelDate(request.getTravelDate())
                .groupSize(groupSize)
                .lockedCostPerPerson(lockedCostPerPerson)
                .lockedAmount(lockedAmount)
                .status(GroupBatch.STATUS_ACTIVE)
                .activeKey(activeKey)
                .build();

        // 先插台账拿到 batchId；唯一索引冲突会在这里抛出，钱还没动
        GroupBatch saved;
        try {
            saved = batchRepository.saveAndFlush(batch);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessConflictException(
                    String.format("场地【%s】在 %s 已被其他批次抢占，请刷新后重试",
                            plan.getVenue(), request.getTravelDate()));
        }

        // 同事务扣款 + 写流水：余额不足抛 BusinessConflictException，整体回滚（台账也回滚）
        String remark = "落地成团 " + batchNo + "：" + plan.getPlanName()
                + "，" + groupSize + " 人 × ¥" + lockedCostPerPerson.toPlainString();
        budgetService.hold(saved.getId(), plan.getId(), lockedAmount,
                templateBudgetNote == null ? remark : remark + "（" + templateBudgetNote + "）");

        log.info("落地成团成功: batchNo={}, plan={}, date={}, venue={}, amount={}",
                batchNo, plan.getId(), request.getTravelDate(), plan.getVenue(), lockedAmount);
        return saved;
    }

    @Override
    public List<GroupBatch> listBatches(String status) {
        if (status == null || status.isBlank()) {
            return batchRepository.findAllByOrderByCreatedAtDesc();
        }
        return batchRepository.findByStatusOrderByCreatedAtDesc(status.trim().toUpperCase());
    }

    @Override
    public GroupBatch getBatch(Long id) {
        return batchRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("落地批次不存在: " + id));
    }

    /** 两道签字用：行锁串行化，保证并发签字/回执生成不会各说各话 */
    private GroupBatch getBatchForUpdate(Long id) {
        return batchRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException("落地批次不存在: " + id));
    }

    @Override
    @Transactional
    public int invalidateBatchesByPlanCostChange(Long planId, BigDecimal newCostPerPerson) {
        List<GroupBatch> activeBatches =
                batchRepository.findByPlanIdAndStatusOrderByCreatedAtDesc(planId, GroupBatch.STATUS_ACTIVE);
        int invalidated = 0;
        for (GroupBatch batch : activeBatches) {
            BigDecimal recalculated = newCostPerPerson.multiply(BigDecimal.valueOf(batch.getGroupSize()));
            // 按落地当时的成团人数重算，金额没变（仍对得上）就什么都不动
            if (recalculated.compareTo(batch.getLockedAmount()) == 0) {
                continue;
            }
            if (batch.isHandedOff()) {
                // 财务口径：两道签字已齐、供应商已按回执备场——批次不作废、钱不退、出行日锁死，
                // 只追加一条金额为 0 的对账说明。
                String remark = "对账说明（交场后改人均）：批次 " + batch.getBatchNo()
                        + " 已交场，供应商已按回执备场，原扣额 ¥" + batch.getLockedAmount().toPlainString()
                        + "（" + batch.getGroupSize() + " 人 × 落地人均 ¥"
                        + batch.getLockedCostPerPerson().toPlainString() + "）不动；"
                        + "方案人均变更为 ¥" + newCostPerPerson.toPlainString()
                        + "，按落地人数 " + batch.getGroupSize() + " 人重算参考额 ¥"
                        + recalculated.toPlainString()
                        + "，差额后续对账处理，不退回池子、批次不作废、出行日锁死";
                budgetService.reconcileNote(batch.getId(), planId, remark);
                log.info("交场批次改人均，只追加对账说明: batchNo={}, poolUnchanged, refAmount={}",
                        batch.getBatchNo(), recalculated);
            } else {
                // 老规则：还没交场，对不上当初扣下的额度就失效并全额退款
                batch.setStatus(GroupBatch.STATUS_INVALID);
                batch.setInvalidReason(GroupBatch.REASON_COST_CHANGED);
                batch.setActiveKey(null);
                batchRepository.save(batch);
                budgetService.refund(batch.getId(), planId, batch.getLockedAmount(),
                        "批次 " + batch.getBatchNo() + " 失效退回：方案人均费用变更为 ¥"
                                + newCostPerPerson.toPlainString()
                                + "，按落地人数 " + batch.getGroupSize()
                                + " 重算为 ¥" + recalculated.toPlainString()
                                + "，与原扣额 ¥" + batch.getLockedAmount().toPlainString() + " 不一致");
                invalidated++;
                log.info("批次因方案费用变化失效: batchNo={}, refund={}",
                        batch.getBatchNo(), batch.getLockedAmount());
            }
        }
        return invalidated;
    }

    @Override
    @Transactional
    public GroupBatch contactArrive(Long id, String signerName) {
        GroupBatch batch = getBatchForUpdate(id);
        if (!GroupBatch.STATUS_ACTIVE.equals(batch.getStatus())) {
            throw new BusinessConflictException(
                    "批次 " + batch.getBatchNo() + " 已失效（预算已退回池子），不能再对接场地供应商");
        }
        if (batch.isHandedOff()) {
            // 场地已经交给供应商：到场记录早已固化，不允许回头补签或改签字人
            throw new BusinessConflictException(
                    "批次 " + batch.getBatchNo() + " 两道签字已齐，场地【" + batch.getVenue()
                            + "】已交给供应商，不能重复签到");
        }
        if (batch.isContactSigned()) {
            // 复核人没签之前，对接人自己再点一次不算完成：只提示，不产生任何状态变化
            throw new BusinessConflictException(
                    "现场对接人 " + batch.getContactPerson() + " 的到场记录已留下，"
                            + "还需科室复核人签字，对接人重复签到不视为完成");
        }
        batch.setContactPerson(signerName.trim());
        batch.setContactSignedAt(LocalDateTime.now());
        batch.setContactSigned(true);
        GroupBatch saved = batchRepository.save(batch);
        log.info("现场对接人到场签字（第一道）: batchNo={}, contact={}",
                batch.getBatchNo(), signerName);
        return saved;
    }

    @Override
    @Transactional
    public GroupBatch reviewSign(Long id, String signerName) {
        GroupBatch batch = getBatchForUpdate(id);
        if (!GroupBatch.STATUS_ACTIVE.equals(batch.getStatus())) {
            throw new BusinessConflictException(
                    "批次 " + batch.getBatchNo() + " 已失效（预算已退回池子），不能再对接场地供应商");
        }
        if (batch.isHandedOff()) {
            // 两个复核人几乎同时签：后到的人必须看到场地已经交给供应商，而不是再生成一笔回执
            throw new BusinessConflictException(
                    "批次 " + batch.getBatchNo() + " 的交场回执已生成，场地【" + batch.getVenue()
                            + "】已交给供应商（复核人：" + batch.getReviewPerson() + "），无需重复签字");
        }
        if (!batch.isContactSigned()) {
            // 顺序不能倒：现场对接人还没留到场记录，复核人不能一个人把字签全
            throw new BusinessConflictException(
                    "批次 " + batch.getBatchNo() + " 还缺现场对接人的到场记录，"
                            + "必须先由现场对接人签到，科室复核人才能复核签字");
        }
        String reviewer = signerName.trim();
        LocalDateTime now = LocalDateTime.now();
        batch.setReviewPerson(reviewer);
        batch.setReviewSignedAt(now);
        batch.setReviewSigned(true);
        batch.setHandedOff(true);

        VendorHandoffReceipt receipt = VendorHandoffReceipt.builder()
                .batchId(batch.getId())
                .batchNo(batch.getBatchNo())
                .venue(batch.getVenue())
                .travelDate(batch.getTravelDate())
                .contactPerson(batch.getContactPerson())
                .contactSignedAt(batch.getContactSignedAt())
                .reviewPerson(reviewer)
                .reviewSignedAt(now)
                .build();
        // batch_id 唯一索引是最终兜底：行锁之外若仍有并发漏网，插入失败则整个事务回滚
        try {
            receiptRepository.saveAndFlush(receipt);
        } catch (DataIntegrityViolationException e) {
            throw new BusinessConflictException(
                    "批次 " + batch.getBatchNo() + " 的交场回执已存在，场地已交给供应商，请刷新后查看");
        }
        GroupBatch saved = batchRepository.save(batch);
        log.info("科室复核人签字（第二道），交场回执生成: batchNo={}, contact={}, review={}",
                batch.getBatchNo(), batch.getContactPerson(), reviewer);
        return saved;
    }

    @Override
    public VendorHandoffReceipt getReceipt(Long batchId) {
        return receiptRepository.findByBatchId(batchId).orElse(null);
    }

    @Override
    @Transactional
    public GroupBatch contactSupplier(Long id) {
        GroupBatch batch = getBatch(id);
        if (!GroupBatch.STATUS_ACTIVE.equals(batch.getStatus())) {
            throw new BusinessConflictException(
                    "批次 " + batch.getBatchNo() + " 已失效（预算已退回池子），不能再对接场地供应商");
        }
        if (!batch.isHandedOff() || !receiptRepository.existsByBatchId(id)) {
            // 两道签字没齐 / 库里没有回执行：台账开关再怎么显示都不算交场
            throw new BusinessConflictException(
                    "批次 " + batch.getBatchNo() + " 还差签字（现场对接人到场记录 + 科室复核人复核），"
                            + "两道签字齐了拿到交场回执后，才能把场地交给供应商");
        }
        return batch;
    }

    private boolean containsRequiredActivities(String suitableActivities, String requiredActivities) {
        if (requiredActivities == null || requiredActivities.isBlank()) {
            return true;
        }
        String suitable = suitableActivities == null ? "" : suitableActivities;
        for (String activity : requiredActivities.split(",")) {
            String trimmed = activity.trim();
            if (!trimmed.isEmpty() && !suitable.contains(trimmed)) {
                return false;
            }
        }
        return true;
    }

}
