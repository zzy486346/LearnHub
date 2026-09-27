<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
const active = ref('course')
const publish = () => ElMessage.info('管理写接口接入后即可发布')
</script>

<template><section><div class="page-title"><div><p class="eyebrow">ADMIN CONSOLE</p><h1>内容管理</h1><p>维护课程内容与营销活动。</p></div></div><el-tabs v-model="active"><el-tab-pane label="课程管理" name="course"><div class="content-card"><div class="section-heading"><h2>课程列表</h2><el-button type="primary" @click="publish">新建课程</el-button></div><el-table :data="[{ title: 'Java 高并发与系统设计', status: 'PUBLISHED', lessons: 24 }]" stripe><el-table-column prop="title" label="课程" /><el-table-column prop="lessons" label="课时" /><el-table-column prop="status" label="状态" /><el-table-column label="操作"><template #default><el-button link type="primary">编辑</el-button></template></el-table-column></el-table></div></el-tab-pane><el-tab-pane label="优惠券管理" name="coupon"><div class="content-card"><div class="section-heading"><h2>优惠券活动</h2><el-button type="primary" @click="publish">新建优惠券</el-button></div><el-empty description="暂无已配置活动" /></div></el-tab-pane></el-tabs></section></template>
