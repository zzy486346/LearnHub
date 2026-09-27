<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { couponApi } from '@/api'
import type { Coupon } from '@/types'
const coupons = ref<Coupon[]>([])
const fallback: Coupon[] = [{ id: 1, name: '新用户学习券', discountAmount: 30, thresholdAmount: 199, availableStock: 999, type: 'NORMAL' }, { id: 2, name: '架构课程限时秒杀券', discountAmount: 100, thresholdAmount: 299, availableStock: 50, type: 'SECKILL' }]
onMounted(async () => { try { coupons.value = (await couponApi.available()).data.data.map(item => ({ id: item.id, name: item.name, discountAmount: 0, availableStock: item.stock, type: item.seckill ? 'SECKILL' : 'NORMAL' })) } catch { coupons.value = fallback } })
async function claim(item: Coupon) { try { if (item.type === 'SECKILL') { const status = (await couponApi.seckill(item.id)).data.data.status; ElMessage.success(`秒杀请求已受理：${status}`) } else { await couponApi.claim(item.id); ElMessage.success('领取成功') } item.claimed = true } catch { ElMessage.error('领取失败，请登录后重试') } }
</script>

<template><section><div class="page-title"><div><p class="eyebrow">LEARNING BENEFITS</p><h1>学习优惠券</h1><p>领取专属权益，让成长更轻松。</p></div></div><div class="coupon-grid"><article v-for="item in coupons" :key="item.id" class="coupon-card"><div class="coupon-value"><template v-if="item.discountAmount"><small>¥</small>{{ item.discountAmount }}</template><template v-else>券</template></div><div><el-tag :type="item.type === 'SECKILL' ? 'danger' : 'success'">{{ item.type === 'SECKILL' ? '限时秒杀' : '普通券' }}</el-tag><h3>{{ item.name }}</h3><p><template v-if="item.thresholdAmount">满 {{ item.thresholdAmount }} 元可用 · </template>剩余 {{ item.availableStock ?? '-' }} 张</p></div><el-button :type="item.type === 'SECKILL' ? 'danger' : 'primary'" :disabled="item.claimed" @click="claim(item)">{{ item.claimed ? '已领取' : item.type === 'SECKILL' ? '立即秒杀' : '立即领取' }}</el-button></article></div></section></template>
