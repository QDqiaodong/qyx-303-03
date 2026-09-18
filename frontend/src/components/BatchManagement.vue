<template>
  <div class="batch-management">
    <el-card class="card pool-card">
      <div class="card-header">
        <h2>公司团建预算池</h2>
        <el-button type="primary" size="small" @click="openAdjustDialog">调整总额</el-button>
      </div>
      <div class="pool-stats" v-loading="poolLoading">
        <div class="pool-item">
          <div class="pool-label">池子总额</div>
          <div class="pool-value total">¥{{ formatMoney(pool.totalAmount) }}</div>
        </div>
        <div class="pool-item">
          <div class="pool-label">已被生效批次占住</div>
          <div class="pool-value occupied">¥{{ formatMoney(pool.occupiedAmount) }}</div>
        </div>
        <div class="pool-item">
          <div class="pool-label">可占用余额（落地认这个）</div>
          <div class="pool-value available">¥{{ formatMoney(pool.availableAmount) }}</div>
        </div>
      </div>
      <div class="pool-note">
        模板上的预算上限只用于建模板；对比是否超预算、批次能否落地，只认这里的可占用余额。
        交场回执齐了之后再改人均：池子里的钱不动，只在流水里追加一条「对账说明」，出行日也锁死。
      </div>
    </el-card>

    <el-card class="card">
      <div class="card-header">
        <h2>落地批次台账</h2>
        <div class="header-actions">
          <el-radio-group v-model="statusFilter" size="small" @change="loadBatches">
            <el-radio-button label="">全部</el-radio-button>
            <el-radio-button label="ACTIVE">生效中</el-radio-button>
            <el-radio-button label="INVALID">已失效</el-radio-button>
          </el-radio-group>
          <el-button size="small" @click="refreshAll">刷新</el-button>
        </div>
      </div>

      <el-table :data="batches" border stripe v-loading="batchLoading">
        <el-table-column prop="batchNo" label="批次号" width="180" />
        <el-table-column prop="planName" label="方案" width="150" show-overflow-tooltip />
        <el-table-column label="落地依据模板" width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.constraintTemplateName || '—' }}
            <span v-if="row.constraintTemplateId" class="template-id">#{{ row.constraintTemplateId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="venue" label="场地" width="120" show-overflow-tooltip />
        <el-table-column label="出行日期" width="120">
          <template #default="{ row }">
            {{ row.travelDate }}
            <el-tooltip v-if="row.handedOff" content="交场回执已生成，出行日已锁死，不再变更" placement="top">
              <el-tag size="small" type="warning" effect="plain" class="lock-tag">已锁</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="groupSize" label="成团人数" width="90" />
        <el-table-column label="锁定人均" width="100">
          <template #default="{ row }">¥{{ row.lockedCostPerPerson }}</template>
        </el-table-column>
        <el-table-column label="扣下金额" width="120">
          <template #default="{ row }">
            <span class="hold-amount">¥{{ formatMoney(row.lockedAmount) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'" size="small">
              {{ row.status === 'ACTIVE' ? '生效中' : '已失效' }}
            </el-tag>
            <div v-if="row.invalidReason === 'PLAN_COST_CHANGED'" class="invalid-reason">
              费用变动·已退款
            </div>
          </template>
        </el-table-column>
        <el-table-column label="两道签字 / 交场回执" width="240">
          <template #default="{ row }">
            <div class="sign-flow">
              <div class="sign-line" :class="{ done: row.contactSigned }">
                <span class="sign-dot">{{ row.contactSigned ? '✓' : '1' }}</span>
                现场对接人到场
                <span v-if="row.contactSigned" class="sign-who">{{ row.contactPerson }}</span>
              </div>
              <div class="sign-line" :class="{ done: row.reviewSigned }">
                <span class="sign-dot">{{ row.reviewSigned ? '✓' : '2' }}</span>
                科室复核人复核
                <span v-if="row.reviewSigned" class="sign-who">{{ row.reviewPerson }}</span>
              </div>
              <div class="sign-line receipt" :class="{ done: row.handedOff }">
                <span class="sign-dot">{{ row.handedOff ? '✓' : '·' }}</span>
                交场回执{{ row.handedOff ? '·场地已交供应商' : '未生成' }}
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="落地时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="供应商交场" width="240" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status !== 'ACTIVE'">
              <el-tooltip content="批次已失效、预算已退回，不能再对接场地供应商" placement="top">
                <el-button size="small" type="info" disabled>对接供应商</el-button>
              </el-tooltip>
            </template>
            <template v-else-if="!row.handedOff">
              <el-button
                size="small"
                :type="row.contactSigned ? 'info' : 'warning'"
                @click="openSignDialog('contact', row)"
              >{{ row.contactSigned ? '到场已签' : '① 到场签到' }}</el-button>
              <el-button
                size="small"
                :type="row.reviewSigned ? 'success' : 'primary'"
                :disabled="!row.contactSigned"
                @click="openSignDialog('review', row)"
              >② 复核签字</el-button>
            </template>
            <template v-else>
              <el-button size="small" type="success" @click="contactSupplier(row)">供应商进场</el-button>
              <el-button size="small" text type="primary" @click="viewReceipt(row)">查看回执</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      <div class="ledger-note">
        交场规则：现场对接人先留到场记录，科室复核人再签，两道签字齐了才生成唯一一笔交场回执、放行供应商；
        复核人没签之前对接人自己再点不算完成。台账开关、两人签字、回执行三处必须同时成立。
      </div>
    </el-card>

    <el-card class="card">
      <div class="card-header">
        <h2>预算进出流水</h2>
        <el-button size="small" @click="loadTransactions">刷新</el-button>
      </div>
      <el-table :data="transactions" border stripe v-loading="txLoading">
        <el-table-column prop="id" label="流水号" width="100" />
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <el-tag :type="txTagType(row.type)" size="small">{{ txTypeText(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="batchId" label="批次ID" width="100">
          <template #default="{ row }">{{ row.batchId || '—' }}</template>
        </el-table-column>
        <el-table-column prop="planId" label="方案ID" width="100">
          <template #default="{ row }">{{ row.planId || '—' }}</template>
        </el-table-column>
        <el-table-column label="金额（占用为正/退回为负/对账为0）" width="220">
          <template #default="{ row }">
            <span :class="amountClass(row.amount)">
              {{ row.amount >= 0 ? '+' : '' }}¥{{ formatMoney(row.amount) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="说明" show-overflow-tooltip />
        <el-table-column prop="createdAt" label="时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog title="调整预算池总额" v-model="showAdjustDialog" width="420px">
      <el-form label-width="100px">
        <el-form-item label="新总额">
          <el-input-number v-model="adjustForm.totalAmount" :min="0" :step="10000" style="width: 100%" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="adjustForm.remark" placeholder="如：Q3 追加团建预算" />
        </el-form-item>
        <div class="adjust-tip">不能把总额调到低于当前已被占住的 ¥{{ formatMoney(pool.occupiedAmount) }}</div>
      </el-form>
      <template #footer>
        <el-button @click="showAdjustDialog = false">取消</el-button>
        <el-button type="primary" :loading="adjusting" @click="submitAdjust">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog
      :title="signDialog.kind === 'contact' ? '现场对接人到场签到（第一道）' : '科室复核人复核签字（第二道）'"
      v-model="showSignDialog" width="440px"
    >
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="批次">
          <span>{{ signDialog.row?.batchNo }} · {{ signDialog.row?.venue }} · {{ signDialog.row?.travelDate }}</span>
        </el-form-item>
        <el-form-item :label="signDialog.kind === 'contact' ? '对接人' : '复核人'">
          <el-input
            v-model="signDialog.name"
            :placeholder="signDialog.kind === 'contact' ? '请输入现场对接人姓名' : '请输入科室复核人姓名'"
            maxlength="60"
          />
        </el-form-item>
        <div class="adjust-tip">
          {{ signDialog.kind === 'contact'
            ? '仅留下到场记录，复核人没签之前场地不会交给供应商；对接人重复点不视为完成。'
            : '两道签字齐了立即生成唯一一笔交场回执，供应商即可进场；交场后池子里的钱与出行日都锁死。' }}
        </div>
      </el-form>
      <template #footer>
        <el-button @click="showSignDialog = false">取消</el-button>
        <el-button type="primary" :loading="signing" @click="submitSign">签字</el-button>
      </template>
    </el-dialog>

    <el-dialog title="场地交场回执" v-model="showReceiptDialog" width="520px">
      <div v-if="receiptDetail" class="receipt-box">
        <div class="receipt-title">场地已交给供应商</div>
        <el-descriptions :column="1" border>
          <el-descriptions-item label="批次号">{{ receiptDetail.batchNo }}</el-descriptions-item>
          <el-descriptions-item label="场地 / 出行日">
            {{ receiptDetail.venue }} · {{ receiptDetail.travelDate }}（出行日已锁死）
          </el-descriptions-item>
          <el-descriptions-item label="① 现场对接人">
            {{ receiptDetail.contactPerson }}（{{ formatDateTime(receiptDetail.contactSignedAt) }}）
          </el-descriptions-item>
          <el-descriptions-item label="② 科室复核人">
            {{ receiptDetail.reviewPerson }}（{{ formatDateTime(receiptDetail.reviewSignedAt) }}）
          </el-descriptions-item>
          <el-descriptions-item label="回执生成时间">
            {{ formatDateTime(receiptDetail.handedOffAt) }}
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <el-empty v-else description="该批次还没有交场回执（两道签字未齐）" />
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { budgetApi, batchApi } from '../api'
import { ElMessage } from 'element-plus'

const pool = reactive({ totalAmount: 0, occupiedAmount: 0, availableAmount: 0 })
const batches = ref([])
const transactions = ref([])
const statusFilter = ref('')
const poolLoading = ref(false)
const batchLoading = ref(false)
const txLoading = ref(false)

const showAdjustDialog = ref(false)
const adjusting = ref(false)
const adjustForm = reactive({ totalAmount: 0, remark: '' })

const showSignDialog = ref(false)
const signing = ref(false)
const signDialog = reactive({ kind: 'contact', row: null, name: '' })

const showReceiptDialog = ref(false)
const receiptDetail = ref(null)

const formatMoney = (v) => {
  if (v === null || v === undefined) return '0.00'
  return Number(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const formatDateTime = (v) => {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 19)
}

const loadPool = async () => {
  poolLoading.value = true
  try {
    const res = await budgetApi.getPool()
    Object.assign(pool, res.data)
  } catch (e) {
    ElMessage.error('预算池加载失败')
  } finally {
    poolLoading.value = false
  }
}

const loadBatches = async () => {
  batchLoading.value = true
  try {
    const res = await batchApi.listBatches(statusFilter.value)
    batches.value = res.data
  } catch (e) {
    ElMessage.error('批次台账加载失败')
  } finally {
    batchLoading.value = false
  }
}

const loadTransactions = async () => {
  txLoading.value = true
  try {
    const res = await budgetApi.getTransactions()
    transactions.value = res.data
  } catch (e) {
    ElMessage.error('预算流水加载失败')
  } finally {
    txLoading.value = false
  }
}

// 三处（回执是否齐、池子数字、供应商能不能进场）看的是同一份提交后状态，刷新时一起拉
const refreshAll = () => {
  loadPool()
  loadBatches()
  loadTransactions()
}

const openAdjustDialog = () => {
  adjustForm.totalAmount = Number(pool.totalAmount)
  adjustForm.remark = ''
  showAdjustDialog.value = true
}

const submitAdjust = async () => {
  adjusting.value = true
  try {
    await budgetApi.adjustTotal({
      totalAmount: adjustForm.totalAmount,
      remark: adjustForm.remark || '行政调整预算池总额'
    })
    ElMessage.success('预算池总额已调整')
    showAdjustDialog.value = false
    refreshAll()
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '调整失败')
  } finally {
    adjusting.value = false
  }
}

const openSignDialog = (kind, row) => {
  signDialog.kind = kind
  signDialog.row = row
  signDialog.name = ''
  showSignDialog.value = true
}

const submitSign = async () => {
  const name = signDialog.name.trim()
  if (!name) {
    ElMessage.warning(signDialog.kind === 'contact' ? '请填写现场对接人姓名' : '请填写科室复核人姓名')
    return
  }
  signing.value = true
  try {
    const api = signDialog.kind === 'contact' ? batchApi.contactArrive : batchApi.reviewSign
    const res = await api(signDialog.row.id, name)
    if (res.data.handedOff) {
      ElMessage.success(`两道签字已齐，交场回执已生成，场地【${res.data.venue}】可交给供应商进场`)
    } else if (signDialog.kind === 'contact') {
      ElMessage.success('现场对接人到场记录已留下，还差科室复核人复核签字')
    } else {
      ElMessage.success('复核签字成功')
    }
    showSignDialog.value = false
    // 关页再开三处口径一致：台账、回执、池子一起刷新
    refreshAll()
  } catch (e) {
    // 并发复核落败 / 重复签到 / 缺第一道：后到者必须看见"场地已经交给供应商"
    ElMessage.error(e.response?.data?.message || '签字失败')
    showSignDialog.value = false
    refreshAll()
  } finally {
    signing.value = false
  }
}

const viewReceipt = async (row) => {
  receiptDetail.value = null
  showReceiptDialog.value = true
  try {
    const res = await batchApi.getReceipt(row.id)
    receiptDetail.value = res.data
  } catch (e) {
    receiptDetail.value = null
  }
}

const contactSupplier = async (row) => {
  try {
    const res = await batchApi.contactSupplier(row.id)
    ElMessage.success(`两道签字已齐，批次 ${res.data.batchNo} 放行：场地【${res.data.venue}】（${res.data.travelDate}）可交供应商进场`)
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '该批次不能对接供应商')
    loadBatches()
  }
}

const txTypeText = (t) => ({
  HOLD: '落地占用',
  REFUND: '失效退回',
  ADJUST: '总额调整',
  RECONCILE: '对账说明'
}[t] || t)
const txTagType = (t) => ({
  HOLD: 'danger',
  REFUND: 'success',
  ADJUST: 'warning',
  RECONCILE: 'primary'
}[t] || '')
const amountClass = (v) => v > 0 ? 'amount-positive' : v < 0 ? 'amount-negative' : 'amount-zero'

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.card {
  margin-bottom: 20px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.card-header h2 {
  font-size: 18px;
  font-weight: 600;
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.pool-stats {
  display: flex;
  gap: 40px;
  padding: 10px 0;
}

.pool-item {
  flex: 1;
  text-align: center;
  padding: 15px;
  background: #f7fafc;
  border-radius: 8px;
}

.pool-label {
  color: #666;
  font-size: 13px;
  margin-bottom: 8px;
}

.pool-value {
  font-size: 24px;
  font-weight: 700;
}

.pool-value.total { color: #409eff; }
.pool-value.occupied { color: #f56c6c; }
.pool-value.available { color: #67c23a; }

.pool-note {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}

.hold-amount {
  color: #f56c6c;
  font-weight: 600;
}

.template-id {
  margin-left: 4px;
  font-size: 12px;
  color: #909399;
}

.invalid-reason {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
}

.lock-tag {
  margin-left: 4px;
}

.sign-flow {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  line-height: 1.4;
}

.sign-line {
  color: #909399;
}

.sign-line.done {
  color: #303133;
}

.sign-line.receipt.done {
  color: #67c23a;
  font-weight: 600;
}

.sign-dot {
  display: inline-block;
  width: 16px;
  height: 16px;
  line-height: 16px;
  text-align: center;
  border-radius: 50%;
  background: #dcdfe6;
  color: #fff;
  font-size: 11px;
  margin-right: 4px;
}

.sign-line.done .sign-dot {
  background: #67c23a;
}

.sign-who {
  color: #409eff;
  margin-left: 4px;
}

.ledger-note {
  margin-top: 10px;
  font-size: 12px;
  color: #909399;
}

.amount-positive {
  color: #f56c6c;
  font-weight: 600;
}

.amount-negative {
  color: #67c23a;
  font-weight: 600;
}

.amount-zero {
  color: #409eff;
  font-weight: 600;
}

.adjust-tip {
  font-size: 12px;
  color: #e6a23c;
}

.receipt-box {
  padding: 0 4px;
}

.receipt-title {
  text-align: center;
  font-size: 16px;
  font-weight: 700;
  color: #67c23a;
  margin-bottom: 14px;
}
</style>
