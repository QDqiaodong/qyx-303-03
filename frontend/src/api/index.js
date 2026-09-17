import axios from 'axios'

const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

export const planApi = {
  createPlan(data) {
    return request.post('/plans', data)
  },
  getAllPlans() {
    return request.get('/plans')
  },
  getPlanById(id) {
    return request.get(`/plans/${id}`)
  },
  updatePlan(id, data) {
    return request.put(`/plans/${id}`, data)
  },
  deletePlan(id) {
    return request.delete(`/plans/${id}`)
  },
  comparePlans(data) {
    return request.post('/plans/compare', data)
  },
  compareAndFilter(data) {
    return request.post('/plans/compare/filter', data)
  }
}

export const constraintApi = {
  createTemplate(data) {
    return request.post('/constraints', data)
  },
  getAllTemplates() {
    return request.get('/constraints')
  },
  getTemplateById(id) {
    return request.get(`/constraints/${id}`)
  },
  updateTemplate(id, data) {
    return request.put(`/constraints/${id}`, data)
  },
  deleteTemplate(id) {
    return request.delete(`/constraints/${id}`)
  },
  saveToRedis(key, data) {
    return request.post(`/constraints/cache/${key}`, data)
  },
  getFromRedis(key) {
    return request.get(`/constraints/cache/${key}`)
  },
  deleteFromRedis(key) {
    return request.delete(`/constraints/cache/${key}`)
  }
}

export const budgetApi = {
  getPool() {
    return request.get('/budget/pool')
  },
  getTransactions() {
    return request.get('/budget/transactions')
  },
  adjustTotal(data) {
    return request.post('/budget/pool/adjust', data)
  }
}

export const batchApi = {
  land(data) {
    return request.post('/batches/land', data)
  },
  listBatches(status) {
    return request.get('/batches', { params: status ? { status } : {} })
  },
  getBatch(id) {
    return request.get(`/batches/${id}`)
  },
  /** 第一道签字：现场对接人到场记录 */
  contactArrive(id, signerName) {
    return request.post(`/batches/${id}/contact-arrive`, { signerName })
  },
  /** 第二道签字：科室复核人复核，齐了生成交场回执 */
  reviewSign(id, signerName) {
    return request.post(`/batches/${id}/review-sign`, { signerName })
  },
  getReceipt(id) {
    return request.get(`/batches/${id}/receipt`)
  },
  contactSupplier(id) {
    return request.post(`/batches/${id}/contact-supplier`)
  }
}