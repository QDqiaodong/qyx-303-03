package com.example.tuanjian;

import com.example.tuanjian.dto.request.ConstraintRequest;
import com.example.tuanjian.dto.request.GroupBatchLandingRequest;
import com.example.tuanjian.dto.request.PlanCreateRequest;
import com.example.tuanjian.dto.response.BudgetPoolView;
import com.example.tuanjian.dto.response.PlanCompareResult;
import com.example.tuanjian.entity.BudgetTransaction;
import com.example.tuanjian.entity.ConstraintCondition;
import com.example.tuanjian.entity.GroupBatch;
import com.example.tuanjian.entity.TeamBuildingPlan;
import com.example.tuanjian.entity.VendorHandoffReceipt;
import com.example.tuanjian.exception.BusinessConflictException;
import com.example.tuanjian.repository.BudgetTransactionRepository;
import com.example.tuanjian.repository.ConstraintConditionRepository;
import com.example.tuanjian.repository.GroupBatchRepository;
import com.example.tuanjian.repository.VendorHandoffReceiptRepository;
import com.example.tuanjian.service.BudgetService;
import com.example.tuanjian.service.ConstraintConditionService;
import com.example.tuanjian.service.GroupBatchService;
import com.example.tuanjian.service.TeamBuildingPlanService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration"
})
class GroupLandingIntegrationTest {

    @Autowired
    private TeamBuildingPlanService planService;
    @Autowired
    private GroupBatchService batchService;
    @Autowired
    private BudgetService budgetService;
    @Autowired
    private ConstraintConditionService constraintService;
    @Autowired
    private ConstraintConditionRepository constraintRepository;
    @Autowired
    private GroupBatchRepository batchRepository;
    @Autowired
    private VendorHandoffReceiptRepository receiptRepository;
    @Autowired
    private BudgetTransactionRepository transactionRepository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    private TeamBuildingPlan plan;

    @BeforeEach
    void setUp() {
        // Spring 上下文/H2 内存库在各测试方法间复用，用原生 SQL 彻底复位，
        // 避免回滚行残留与 JPA 一级缓存陈旧值相互干扰
        transactionTemplate.executeWithoutResult(status -> {
            entityManager.createNativeQuery("delete from vendor_handoff_receipt").executeUpdate();
            entityManager.createNativeQuery("delete from budget_transaction").executeUpdate();
            entityManager.createNativeQuery("delete from group_batch").executeUpdate();
            entityManager.createNativeQuery("delete from constraint_condition").executeUpdate();
            entityManager.createNativeQuery("delete from team_building_plan").executeUpdate();
            entityManager.createNativeQuery(
                    "update budget_pool set total_amount=10000, occupied_amount=0 where id=1").executeUpdate();
            entityManager.clear();
        });

        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        plan = planService.createPlan(PlanCreateRequest.builder()
                .planName("山地拓展方案")
                .transportation("大巴")
                .venue("青云山营地")
                .projects("拓展,烧烤")
                .costPerPerson(new BigDecimal("300.00"))
                .durationDays(2)
                .minParticipants(10)
                .maxParticipants(50)
                .suitableActivities("户外拓展,聚餐,露营")
                .build());
    }

    private GroupBatchLandingRequest landingRequest(long planId, String date, int size) {
        return landingRequest(planId, date, size, null);
    }

    private GroupBatchLandingRequest landingRequest(long planId, String date, int size, Long templateId) {
        return GroupBatchLandingRequest.builder()
                .planId(planId)
                .travelDate(LocalDate.parse(date))
                .groupSize(size)
                .maxDurationDays(3)
                .requiredActivities("户外拓展")
                .templateId(templateId)
                .build();
    }

    private ConstraintRequest constraint(BigDecimal templateLimit, int people) {
        return ConstraintRequest.builder()
                .templateName("测试模板")
                .budgetLimit(templateLimit)
                .maxDurationDays(3)
                .participantCount(people)
                .requiredActivities("户外拓展")
                .build();
    }

    private ConstraintCondition createTemplate() {
        return constraintService.createTemplate(ConstraintRequest.builder()
                .templateName("测试模板")
                .budgetLimit(new BigDecimal("999999.00"))
                .maxDurationDays(3)
                .participantCount(20)
                .requiredActivities("户外拓展")
                .build());
    }

    @Test
    void deletedTemplate_disappearsFromList_andCannotCompareOrLand_butExistingBatchStays() {
        ConstraintCondition template = createTemplate();
        GroupBatchLandingRequest landReq = landingRequest(plan.getId(), "2029-01-01", 20, template.getId());
        GroupBatch batch = batchService.land(landReq, null);
        BigDecimal occupiedBeforeDelete = budgetService.getPool().getOccupiedAmount();

        constraintService.deleteTemplate(template.getId());

        assertTrue(constraintService.getAllTemplates().stream().noneMatch(t -> t.getId().equals(template.getId())),
                "已删模板必须从列表入口消失");
        assertEquals(ConstraintCondition.STATUS_DELETED,
                constraintService.getTemplateById(template.getId()).getStatus(),
                "删除是状态留痕，不能误动已落地批次的历史依据");

        ConstraintRequest deletedRequest = ConstraintRequest.builder()
                .templateId(template.getId())
                .templateName(template.getTemplateName())
                .budgetLimit(template.getBudgetLimit())
                .maxDurationDays(template.getMaxDurationDays())
                .participantCount(template.getParticipantCount())
                .requiredActivities(template.getRequiredActivities())
                .build();
        assertThrows(NoSuchElementException.class, () -> planService.comparePlans(deletedRequest));

        GroupBatchLandingRequest landFromDeleted =
                landingRequest(plan.getId(), "2029-01-02", 20, template.getId());
        assertThrows(NoSuchElementException.class, () -> batchService.land(landFromDeleted, null));

        GroupBatch reloaded = batchService.getBatch(batch.getId());
        assertEquals(GroupBatch.STATUS_ACTIVE, reloaded.getStatus(), "删除模板不冲掉已生效批次");
        assertNotNull(reloaded.getActiveKey(), "删除模板不释放已占场地");
        assertEquals(0, occupiedBeforeDelete.compareTo(budgetService.getPool().getOccupiedAmount()),
                "删除模板不能改变预算占用");
        assertEquals(1L, batchRepository.count());
        assertEquals(1, transactionRepository.findAll().stream()
                .filter(t -> "HOLD".equals(t.getType())).count());
    }

    @Test
    void concurrentTemplateDelete_onlyOneSucceeds_otherSeesMissingTemplate() throws Exception {
        ConstraintCondition template = createTemplate();
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    constraintService.deleteTemplate(template.getId());
                    success.incrementAndGet();
                } catch (NoSuchElementException e) {
                    missing.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get());
        assertEquals(1, missing.get());
        assertEquals(ConstraintCondition.STATUS_DELETED, constraintRepository.findById(template.getId()).orElseThrow().getStatus());
    }

    @Test
    void land_createsBatchAndHoldsBudgetAtomically() {
        GroupBatch batch = batchService.land(landingRequest(plan.getId(), "2026-10-01", 20), null);

        assertEquals(GroupBatch.STATUS_ACTIVE, batch.getStatus());
        assertEquals(0, new BigDecimal("6000.00").compareTo(batch.getLockedAmount()));

        BudgetPoolView pool = budgetService.getPool();
        // 初始 10000，占 6000，剩 4000；批次台账和 HOLD 流水同时存在
        assertEquals(0, new BigDecimal("6000.00").compareTo(pool.getOccupiedAmount()));
        assertEquals(0, new BigDecimal("4000.00").compareTo(pool.getAvailableAmount()));
        assertTrue(transactionRepository.findAll().stream()
                .anyMatch(t -> "HOLD".equals(t.getType())
                        && t.getBatchId().equals(batch.getId())
                        && t.getAmount().compareTo(new BigDecimal("6000.00")) == 0));
    }

    @Test
    void land_failsWhenPoolBalanceInsufficient_andRollsEverythingBack() {
        // 池子里只有 10000，40 人 × 300 = 12000 > 余额
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.land(landingRequest(plan.getId(), "2026-10-02", 40), null));
        assertTrue(ex.getMessage().contains("余额不足"));

        // 台账没留下、钱没扣
        assertEquals(0, batchRepository.count());
        assertEquals(0, budgetService.getPool().getOccupiedAmount().compareTo(BigDecimal.ZERO));
        assertEquals(0, transactionRepository.count());
    }

    @Test
    void sameDaySameVenue_onlyOneActiveBatch_evenWhenConcurrent() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    batchService.land(landingRequest(plan.getId(), "2026-11-11", 10), null);
                    success.incrementAndGet();
                } catch (BusinessConflictException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "同一天同一场地只能成一条");
        assertEquals(threads - 1, conflict.get(), "后到的人应看到场地已占");

        // 预算只被扣一次
        assertEquals(0, new BigDecimal("3000.00").compareTo(budgetService.getPool().getOccupiedAmount()));
        assertEquals(1, transactionRepository.findAll().stream()
                .filter(t -> "HOLD".equals(t.getType())).count());
    }

    @Test
    void compare_usesPoolBalance_notTemplateLimit() {
        // 模板上限写成 999999（对账口径算合规），但池子余额只剩 10000
        List<PlanCompareResult> results = planService.comparePlans(
                constraint(new BigDecimal("999999.00"), 40));
        PlanCompareResult result = results.stream()
                .filter(r -> r.getPlanId().equals(plan.getId())).findFirst().orElseThrow();

        // 12000 > 池子余额 10000：落地口径不合规
        assertFalse(result.getIsBudgetCompliant());
        assertFalse(result.getIsAllCompliant());
        // 但按模板上限算是合规的——仅作对账参考
        assertTrue(result.getIsTemplateBudgetCompliant());

        // 先落一条占用 6000，余额变 4000，20 人的方案（6000）也不能再被筛成合规
        batchService.land(landingRequest(plan.getId(), "2026-12-01", 20), null);
        List<PlanCompareResult> after = planService.comparePlans(
                constraint(new BigDecimal("999999.00"), 20));
        PlanCompareResult afterResult = after.stream()
                .filter(r -> r.getPlanId().equals(plan.getId())).findFirst().orElseThrow();
        assertFalse(afterResult.getIsBudgetCompliant(), "已扣出去的钱不能再让第二套方案筛成合规");
        assertEquals(1, afterResult.getActiveBatchCount());
    }

    @Test
    void changingPlanCost_beforeHandoff_invalidatesBatchAndRefunds_andBlocksSupplierContact() {
        GroupBatch batch = batchService.land(landingRequest(plan.getId(), "2027-01-01", 20), null);
        assertEquals(0, new BigDecimal("6000.00").compareTo(budgetService.getPool().getOccupiedAmount()));

        // 还没交场：供应商不能进场（两道签字没齐，库里也没有回执）
        BusinessConflictException before = assertThrows(BusinessConflictException.class,
                () -> batchService.contactSupplier(batch.getId()));
        assertTrue(before.getMessage().contains("还差签字"));

        // 只签第一道（现场对接人到场）仍然不能进场；对接人自己再点一次不算完成
        batchService.contactArrive(batch.getId(), "现场对接人老王");
        BusinessConflictException oneSig = assertThrows(BusinessConflictException.class,
                () -> batchService.contactSupplier(batch.getId()));
        assertTrue(oneSig.getMessage().contains("还差签字"));
        BusinessConflictException repeat = assertThrows(BusinessConflictException.class,
                () -> batchService.contactArrive(batch.getId(), "现场对接人老王"));
        assertTrue(repeat.getMessage().contains("对接人重复签到"));
        assertFalse(batchService.getBatch(batch.getId()).isHandedOff());
        assertNull(batchService.getReceipt(batch.getId()));

        // 人均费用 300 -> 350：还没交场，按老规则失效、退款
        plan.setCostPerPerson(new BigDecimal("350.00"));
        planService.updatePlan(plan.getId(), PlanCreateRequest.builder()
                .planName(plan.getPlanName())
                .transportation(plan.getTransportation())
                .venue(plan.getVenue())
                .projects(plan.getProjects())
                .costPerPerson(new BigDecimal("350.00"))
                .durationDays(plan.getDurationDays())
                .minParticipants(plan.getMinParticipants())
                .maxParticipants(plan.getMaxParticipants())
                .suitableActivities(plan.getSuitableActivities())
                .build());

        GroupBatch reloaded = batchService.getBatch(batch.getId());
        assertEquals(GroupBatch.STATUS_INVALID, reloaded.getStatus());
        assertEquals(GroupBatch.REASON_COST_CHANGED, reloaded.getInvalidReason());
        assertNull(reloaded.getActiveKey());

        // 钱退回池子
        assertEquals(0, BigDecimal.ZERO.compareTo(budgetService.getPool().getOccupiedAmount()));
        assertEquals(0, new BigDecimal("10000.00").compareTo(budgetService.getPool().getAvailableAmount()));
        assertTrue(transactionRepository.findAll().stream()
                .anyMatch(t -> "REFUND".equals(t.getType())
                        && t.getBatchId().equals(batch.getId())
                        && t.getAmount().compareTo(new BigDecimal("-6000.00")) == 0));

        // 失效批次不能再对接场地供应商
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.contactSupplier(batch.getId()));
        assertTrue(ex.getMessage().contains("不能再对接场地供应商"));

        // activeKey 已释放：同一天同一场地允许新的生效批次落地
        TeamBuildingPlan plan2 = planService.createPlan(PlanCreateRequest.builder()
                .planName("另一套方案")
                .transportation("自驾")
                .venue("青云山营地")
                .projects("徒步")
                .costPerPerson(new BigDecimal("200.00"))
                .durationDays(1)
                .minParticipants(5)
                .maxParticipants(40)
                .suitableActivities("户外拓展")
                .build());
        GroupBatch again = batchService.land(landingRequest(plan2.getId(), "2027-01-01", 10), null);
        assertEquals(GroupBatch.STATUS_ACTIVE, again.getStatus());
    }

    @Test
    void reviewSignBeforeContactArrive_isRejected() {
        GroupBatch batch = batchService.land(landingRequest(plan.getId(), "2027-05-01", 20), null);
        // 复核人不能跳过现场到场记录直接签
        BusinessConflictException ex = assertThrows(BusinessConflictException.class,
                () -> batchService.reviewSign(batch.getId(), "复核人老李"));
        assertTrue(ex.getMessage().contains("还缺现场对接人"));
        assertFalse(batchService.getBatch(batch.getId()).isReviewSigned());
        assertNull(batchService.getReceipt(batch.getId()));
    }

    @Test
    void twoSignaturesComplete_createsSingleReceipt_andAllowsSupplierEntry() {
        GroupBatch batch = batchService.land(landingRequest(plan.getId(), "2027-06-01", 20), null);

        batchService.contactArrive(batch.getId(), "现场对接人老王");
        GroupBatch afterFirst = batchService.getBatch(batch.getId());
        assertTrue(afterFirst.isContactSigned());
        assertFalse(afterFirst.isReviewSigned());
        assertFalse(afterFirst.isHandedOff(), "只有第一道签字不算交场");
        assertNull(batchService.getReceipt(batch.getId()));

        batchService.reviewSign(batch.getId(), "科室复核人老李");
        GroupBatch afterSecond = batchService.getBatch(batch.getId());
        assertTrue(afterSecond.isContactSigned());
        assertTrue(afterSecond.isReviewSigned());
        assertTrue(afterSecond.isHandedOff());

        // 库里确实有一笔回执，且与台账口径一致
        VendorHandoffReceipt receipt = batchService.getReceipt(batch.getId());
        assertNotNull(receipt);
        assertEquals(batch.getId(), receipt.getBatchId());
        assertEquals("现场对接人老王", receipt.getContactPerson());
        assertEquals("科室复核人老李", receipt.getReviewPerson());
        assertEquals(1, receiptRepository.count());

        // 两道齐了才允许供应商进场
        assertDoesNotThrow(() -> batchService.contactSupplier(batch.getId()));

        // 交场后再补签任一道都被拒（后到的人看到的是"场地已经交给供应商"）
        BusinessConflictException contactAgain = assertThrows(BusinessConflictException.class,
                () -> batchService.contactArrive(batch.getId(), "另一个对接人"));
        assertTrue(contactAgain.getMessage().contains("已交给供应商"));
        BusinessConflictException reviewAgain = assertThrows(BusinessConflictException.class,
                () -> batchService.reviewSign(batch.getId(), "另一个复核人"));
        assertTrue(reviewAgain.getMessage().contains("已交给供应商"));

        // 回执仍然只有一笔
        assertEquals(1, receiptRepository.count());
    }

    @Test
    void concurrentReviewSigns_onlyOneReceipt_loserSeesVenueHandedOff() throws Exception {
        GroupBatch batch = batchService.land(landingRequest(plan.getId(), "2027-07-01", 20), null);
        // 第一道签字先落稳
        batchService.contactArrive(batch.getId(), "现场对接人老王");

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger handedOffConflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    batchService.reviewSign(batch.getId(), "复核人" + idx);
                    success.incrementAndGet();
                } catch (BusinessConflictException e) {
                    if (e.getMessage().contains("已交给供应商")) {
                        handedOffConflict.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (Exception e) {
                    other.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "两道签字只能产生一笔回执");
        assertEquals(threads - 1, handedOffConflict.get(), "后到的复核人必须看到场地已交供应商");
        assertEquals(0, other.get());
        assertEquals(1, receiptRepository.count(), "库里只允许一条回执行");
        assertTrue(batchService.getBatch(batch.getId()).isHandedOff());
    }

    @Test
    void changingPlanCost_afterHandoff_keepsPoolBatchAndDate_onlyAddsReconcileNote() {
        GroupBatch batch = batchService.land(landingRequest(plan.getId(), "2027-08-01", 20), null);
        BigDecimal occupiedBefore = budgetService.getPool().getOccupiedAmount();
        assertEquals(0, new BigDecimal("6000.00").compareTo(occupiedBefore));

        // 两道签字齐，回执生成，场地交给供应商
        batchService.contactArrive(batch.getId(), "现场对接人老王");
        batchService.reviewSign(batch.getId(), "科室复核人老李");
        assertNotNull(batchService.getReceipt(batch.getId()));
        LocalDate travelDateBefore = batchService.getBatch(batch.getId()).getTravelDate();

        // 行政改人均 300 -> 260：财务口径——供应商已按回执备场，只追加对账说明
        planService.updatePlan(plan.getId(), PlanCreateRequest.builder()
                .planName(plan.getPlanName())
                .transportation(plan.getTransportation())
                .venue(plan.getVenue())
                .projects(plan.getProjects())
                .costPerPerson(new BigDecimal("260.00"))
                .durationDays(plan.getDurationDays())
                .minParticipants(plan.getMinParticipants())
                .maxParticipants(plan.getMaxParticipants())
                .suitableActivities(plan.getSuitableActivities())
                .build());

        GroupBatch after = batchService.getBatch(batch.getId());
        assertEquals(GroupBatch.STATUS_ACTIVE, after.getStatus(), "交场后改人均不作废批次");
        assertTrue(after.isHandedOff());
        assertNotNull(after.getActiveKey(), "场地占位不释放");
        assertEquals(travelDateBefore, after.getTravelDate(), "出行日锁死");
        assertEquals(0, new BigDecimal("6000.00").compareTo(after.getLockedAmount()),
                "原扣额不变");

        // 池子里的钱一分不动
        assertEquals(0, occupiedBefore.compareTo(budgetService.getPool().getOccupiedAmount()));
        assertEquals(0, new BigDecimal("4000.00").compareTo(budgetService.getPool().getAvailableAmount()));
        assertFalse(transactionRepository.findAll().stream()
                .anyMatch(t -> "REFUND".equals(t.getType())), "不允许退款");

        // 必须多一条金额为 0 的 RECONCILE 对账说明，否则不算做完
        List<BudgetTransaction> notes = transactionRepository.findAll().stream()
                .filter(t -> "RECONCILE".equals(t.getType())
                        && t.getBatchId().equals(batch.getId())).toList();
        assertEquals(1, notes.size(), "交场后改价必须落一条对账说明");
        assertEquals(0, BigDecimal.ZERO.compareTo(notes.get(0).getAmount()));
        assertTrue(notes.get(0).getRemark().contains("对账说明"));
        assertTrue(notes.get(0).getRemark().contains("不退回池子"));

        // 回执还在，供应商照样能进场
        assertNotNull(batchService.getReceipt(batch.getId()));
        assertDoesNotThrow(() -> batchService.contactSupplier(batch.getId()));
    }

    @Test
    void sameCostValueKeepsBatchActive() {
        GroupBatch batch = batchService.land(landingRequest(plan.getId(), "2027-02-01", 20), null);
        // 改了别的字段，人均费用还是 300：批次不动
        planService.updatePlan(plan.getId(), PlanCreateRequest.builder()
                .planName(plan.getPlanName())
                .transportation("高铁")
                .venue(plan.getVenue())
                .projects(plan.getProjects())
                .costPerPerson(new BigDecimal("300.00"))
                .durationDays(plan.getDurationDays())
                .minParticipants(plan.getMinParticipants())
                .maxParticipants(plan.getMaxParticipants())
                .suitableActivities(plan.getSuitableActivities())
                .build());
        assertEquals(GroupBatch.STATUS_ACTIVE, batchService.getBatch(batch.getId()).getStatus());
        assertEquals(0, new BigDecimal("6000.00").compareTo(budgetService.getPool().getOccupiedAmount()));
    }    @Test
    void landingRejectsNonCompliantPlan() {
        // 必备活动不满足
        GroupBatchLandingRequest req = landingRequest(plan.getId(), "2027-03-01", 20);
        req.setRequiredActivities("温泉");
        assertThrows(BusinessConflictException.class, () -> batchService.land(req, null));

        // 人数超出方案区间
        assertThrows(BusinessConflictException.class,
                () -> batchService.land(landingRequest(plan.getId(), "2027-03-02", 99), null));

        // 天数超限
        GroupBatchLandingRequest shortLimit = landingRequest(plan.getId(), "2027-03-03", 20);
        shortLimit.setMaxDurationDays(1);
        assertThrows(BusinessConflictException.class, () -> batchService.land(shortLimit, null));

        assertEquals(0, batchRepository.count());
        assertEquals(0, BigDecimal.ZERO.compareTo(budgetService.getPool().getOccupiedAmount()));
    }

    @Test
    void planWithActiveBatchCannotBeDeleted() {
        batchService.land(landingRequest(plan.getId(), "2027-04-01", 20), null);
        assertThrows(BusinessConflictException.class, () -> planService.deletePlan(plan.getId()));
    }
}
