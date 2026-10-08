<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { couponApi } from '@/api'
import { isSessionExpired } from '@/api/session'
import { useAuthStore } from '@/stores/auth'
import type { Coupon, MyCoupon } from '@/types'
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const coupons = ref<Coupon[]>([])
const wallet = ref<MyCoupon[]>([])
const loading = ref(false)
const loadError = ref('')
const claiming = ref<string[]>([])
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
let polls = 0

function owned(item: Coupon) { return wallet.value.find(entry => entry.couponId === item.id) }
function claimed(item: Coupon) { return ['AVAILABLE', 'UPCOMING', 'USED', 'EXPIRED'].includes(owned(item)?.status || '') }
function pending(item: Coupon) { return owned(item)?.status === 'RESERVED' }
function activityStatus(item: Coupon) {
  if (Date.now() >= new Date(item.endAt).getTime()) return 'ENDED'
  if (Date.now() < new Date(item.startAt).getTime()) return 'UPCOMING'
  return item.status
}
function label(item: Coupon) {
  if (claiming.value.includes(item.id)) return '提交中'
  if (claimed(item)) return '已领取'
  if (pending(item)) return '处理中'
  return ({ UPCOMING: '尚未开始', ENDED: '已结束', DISABLED: '已下架', SOLD_OUT: '已抢光' } as Record<string, string>)[activityStatus(item)]
    || (item.seckill ? '立即秒杀' : '立即领取')
}
function formatDate(value: string) { return new Date(value).toLocaleString('zh-CN') }
function formatMoney(value: number) { return Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 }) }

async function load(background = false) {
  clearTimeout(timer)
  const current = ++generation
  if (!background) loading.value = true
  loadError.value = ''
  try {
    const [available, mine] = await Promise.all([
      couponApi.available(),
      auth.authenticated ? couponApi.mine() : Promise.resolve(null),
    ])
    if (current !== generation) return
    coupons.value = available.data.data
    wallet.value = mine?.data.data || []
    if (wallet.value.some(item => item.status === 'RESERVED') && polls++ < 30) {
      timer = setTimeout(() => void load(true), 2000)
    }
  } catch (error) {
    if (isSessionExpired(error)) return
    if (current === generation) loadError.value = (error as { response?: { data?: { message?: string } } }).response?.data?.message || '优惠券加载失败，请重试'
  } finally {
    if (current === generation) loading.value = false
  }
}

async function claim(item: Coupon) {
  if (claiming.value.includes(item.id) || claimed(item) || pending(item)) return
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (activityStatus(item) !== 'ACTIVE') {
    ElMessage.warning(`当前活动${label(item)}`)
    await load()
    return
  }
  claiming.value.push(item.id)
  try {
    const result = (await (item.seckill ? couponApi.seckill(item.id) : couponApi.claim(item.id))).data.data
    if (result.status === 'CONFIRMED') ElMessage.success('领取成功，可在个人中心查看')
    else if (result.status === 'RESERVED') ElMessage.info('秒杀请求处理中，结果会自动更新，也可到个人中心查看')
    else ElMessage.error('领取未成功，请查看活动状态后重试')
  } catch (error) {
    if (isSessionExpired(error)) return
    const response = (error as { response?: { status?: number; data?: { message?: string } } }).response
    ElMessage.error(response?.status === 401 ? '登录已失效，请重新登录' : response?.data?.message || '领取请求未确认，请刷新查看结果')
  } finally {
    claiming.value = claiming.value.filter(id => id !== item.id)
    polls = 0
    await load(true)
  }
}

watch(() => auth.authenticated, () => { polls = 0; void load() }, { immediate: true })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>

<template>
  <section>
    <div class="page-title">
      <div><p class="eyebrow">LEARNING BENEFITS</p><h1>学习优惠券</h1><p>领取专属权益，让成长更轻松。</p></div>
      <RouterLink to="/profile">我的优惠券 →</RouterLink>
    </div>
    <div v-if="loadError" class="content-card state-panel" role="alert"><p>{{ loadError }}</p><el-button @click="polls = 0; load()">重新加载</el-button></div>
    <p v-if="wallet.some(item => item.status === 'RESERVED')" role="status">秒杀请求正在确认，稍后可点击 <el-button text @click="polls = 0; load()">刷新结果</el-button></p>
    <div class="coupon-grid" v-loading="loading">
      <article v-for="item in coupons" :key="item.id" class="coupon-card">
        <div class="coupon-value"><small>¥</small>{{ formatMoney(item.discountAmount) }}</div>
        <div>
          <el-tag :type="item.seckill ? 'danger' : 'success'">{{ item.seckill ? '限时秒杀' : '普通券' }}</el-tag>
          <h3>{{ item.name }}</h3>
          <p>{{ item.thresholdAmount > 0 ? `满 ${formatMoney(item.thresholdAmount)} 元可用` : '无门槛' }} · 剩余 {{ item.stock }} 张</p>
          <small>领取截止：{{ formatDate(item.endAt) }}</small>
        </div>
        <el-button :type="item.seckill ? 'danger' : 'primary'" :loading="claiming.includes(item.id)"
          :disabled="claimed(item) || pending(item) || activityStatus(item) !== 'ACTIVE'" @click="claim(item)">{{ label(item) }}</el-button>
      </article>
    </div>
    <el-empty v-if="!loading && !loadError && !coupons.length" description="暂无优惠券活动" />
  </section>
</template>
