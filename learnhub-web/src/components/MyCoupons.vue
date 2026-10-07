<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { couponApi } from '@/api'
import type { MyCoupon } from '@/types'

const coupons = ref<MyCoupon[]>([])
const loading = ref(true)
const error = ref('')
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
let polls = 0
const labels: Record<MyCoupon['status'], string> = {
  AVAILABLE: '可使用', UPCOMING: '未到使用时间', USED: '已使用', EXPIRED: '已过期', RESERVED: '领取处理中', REJECTED: '领取失败',
}
function money(value: number) { return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }
function date(value: string) { return new Date(value).toLocaleString('zh-CN') }

async function load(background = false) {
  clearTimeout(timer)
  const current = ++generation
  if (!background) loading.value = true
  error.value = ''
  try {
    const result = (await couponApi.mine()).data.data
    if (current !== generation) return
    coupons.value = result
    if (result.some(item => item.status === 'RESERVED') && polls++ < 30) {
      timer = setTimeout(() => void load(true), 2000)
    }
  } catch {
    if (current === generation) error.value = '优惠券暂时加载失败，请重试'
  } finally {
    if (current === generation) loading.value = false
  }
}
onMounted(() => void load())
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>

<template>
  <section class="content-card wallet" :aria-busy="loading">
    <div class="profile-section-heading">
      <div><h2>我的优惠券</h2><p>查看领取结果、使用门槛和有效期。</p></div>
      <el-button :loading="loading" @click="polls = 0; load()">刷新</el-button>
    </div>
    <div v-if="loading" class="profile-state" role="status">正在加载优惠券…</div>
    <div v-else-if="error" class="profile-state profile-state-error" role="alert">{{ error }}</div>
    <div v-else-if="coupons.length" class="wallet-list">
      <article v-for="item in coupons" :key="item.couponId" class="wallet-item">
        <div class="wallet-value">¥{{ money(item.discountAmount) }}</div>
        <div class="wallet-detail">
          <h3>{{ item.name }} <el-tag :type="item.status === 'AVAILABLE' ? 'success' : item.status === 'RESERVED' ? 'warning' : 'info'">{{ labels[item.status] }}</el-tag></h3>
          <p>{{ item.thresholdAmount > 0 ? `满 ${money(item.thresholdAmount)} 元可用` : '无门槛' }} · {{ item.type === 'SECKILL' ? '秒杀券' : '普通券' }}</p>
          <small>有效期：{{ date(item.useStartAt) }} 至 {{ date(item.useEndAt) }}</small>
          <small>领取时间：{{ date(item.claimedAt) }}</small>
          <p v-if="item.status === 'RESERVED'" role="status">结果正在确认，请稍后刷新。</p>
          <RouterLink v-if="item.status === 'REJECTED'" to="/coupons">查看优惠券活动</RouterLink>
        </div>
      </article>
    </div>
    <div v-else class="profile-state profile-empty-state">
      <div><strong>还没有优惠券</strong><span>领取成功后，优惠券会保存在这里。</span></div>
      <RouterLink to="/coupons">去领券</RouterLink>
    </div>
  </section>
</template>

<style scoped>
.wallet { margin-top: 24px; }
.wallet-list { display: grid; gap: 16px; }
.wallet-item { display: flex; align-items: flex-start; gap: 24px; padding: 20px; border: 1px solid var(--line); border-radius: 16px; }
.wallet-value { flex: none; min-width: 90px; color: var(--primary); font-size: 32px; font-weight: 800; }
.wallet-detail { min-width: 0; }
.wallet-detail h3 { margin: 0; display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.wallet-detail p { margin: 10px 0; }
.wallet-detail small { display: block; color: var(--muted); line-height: 1.7; }
@media (max-width: 600px) { .wallet-item { flex-direction: column; gap: 12px; padding: 16px; } }
</style>
