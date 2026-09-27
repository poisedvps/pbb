<template>
  <el-card>
    <template #header><b>{{ title }}</b></template>
    <el-empty :description="`「${title}」页面待实现（${ticket}）`" />
  </el-card>
</template>

<script setup>
defineProps({ title: String, ticket: String })
</script>
