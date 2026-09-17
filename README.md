# qyx-303 企业团建方案多方案条件对比择优推演系统

## 项目简介
企业团建方案多方案条件对比择优推演系统，包含 Spring Boot 后端、Vue/Vite 前端、MySQL 和 Redis。
在方案登记、约束模板、对比择优之上，新增"落地成团"：从当次对比中挑一份当时合规的方案落成批次，
同时从公司团建预算池扣占预算、占住出行当天的场地。

## 预算口径（重要）
- 模板上的「预算上限」只用于**建模板**，以及对比页给对账的人和当初建模板的数字核对（页面标注为"对账口径"）。
- 对比里**算不算超预算**、批次**能不能落下去**，只认预算池当时**还没被占住的余额**：
  可占用余额 = 池子总额 − 所有生效批次已扣金额之和。两套口径冲突时，落地口径优先。

## 落地成团规则
- 批次台账（group_batch）与预算进出流水（budget_transaction）在同一个数据库事务内做成，少一头整体回滚。
- 同一天、同一场地只允许一条生效批次（active_key 部分唯一索引兜底），并发抢占只有一条成功，后到者收到 409 且预算不重复扣。
- **交场前**已落地方案的人均费用被修改：按落地当时成团人数重算，与原扣额对不上的批次立即失效、钱全额退回池子，且不能再对接场地供应商。
- **交场后**（两道签字齐、回执已生成、供应商已按回执备场）方案人均被修改：按财务口径，批次不作废、池子里的钱一分不动、出行日锁死，只在预算流水里追加一条金额为 0 的「对账说明」（RECONCILE）。
- 不允许只在对比结果上勾选"已选定"而背后没有批次、没有预算进出；选定方案的唯一入口是真正落成批次。
- 预算池初始总额由配置 `tuanjian.budget.initial-total` 控制（默认 200000），也可在"落地批次与预算池"页调整。

## 供应商交场（两道签字 + 唯一回执）
- 第一道：现场对接人在批次上留到场记录（`POST /api/batches/{id}/contact-arrive`，带签字人姓名）。
- 第二道：科室复核人复核签字（`POST /api/batches/{id}/review-sign`）。
- 两道签字**都齐了**才在同一事务里生成**唯一一笔**交场回执（vendor_handoff_receipt，batch_id 唯一索引 + 批次行悲观锁兜底），
  批次 `handedOff` 置真，此时才允许把场地交给供应商。
- 复核人没签之前，对接人自己再点一次只返回 409、不产生任何状态变化，不算完成；复核人也不能跳过到场记录先签。
- 两个复核人几乎同时签：系统只留一笔回执，后到者收到 409，并看到"场地已经交给供应商"。
- 台账开关、库里两人签字、回执行三处必须同时成立；只改开关没有签字/回执，供应商进场接口直接拒绝。
- 关页再开，回执是否齐（GET 回执 200/404）、池子数字、供应商能否进场，读的都是同一份已提交状态。

## 主要 API
- `POST /api/batches/land` 落地成团（planId、travelDate、groupSize、maxDurationDays、requiredActivities）
- `GET  /api/batches?status=ACTIVE|INVALID` 批次台账
- `POST /api/batches/{id}/contact-arrive` 第一道签字：现场对接人到场记录（{signerName}）
- `POST /api/batches/{id}/review-sign` 第二道签字：科室复核人复核，齐了生成唯一回执（{signerName}）
- `GET  /api/batches/{id}/receipt` 交场回执（两道未齐返回 404）
- `POST /api/batches/{id}/contact-supplier` 供应商进场（仅两道签字齐、回执已在的生效批次放行；失效批次被拒）
- `GET  /api/budget/pool` 预算池（总额/已占用/可占用余额）
- `GET  /api/budget/transactions` 预算进出流水（HOLD/REFUND/ADJUST/RECONCILE）
- `POST /api/budget/pool/adjust` 调整池子总额

## 前端访问地址
- 默认地址：http://localhost:8203
- 127.0.0.1：http://127.0.0.1:8203

## 端口
- 前端：8203
- 后端 API：8303
- MySQL：3403
- Redis：6503

## 启动命令
```bash
sh start.sh
```

## 验证命令
```bash
cd backend && mvn compile -q
cd ../frontend && npm ci && npm run build
cd .. && docker compose up -d --build
curl -sS http://localhost:8203
curl -sS http://127.0.0.1:8203
```
