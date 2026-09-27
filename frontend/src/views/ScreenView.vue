<template>
  <div class="screen">
    <div class="head">
      <div class="title">信息科排班表</div>
      <div class="clock">{{ now }}</div>
    </div>
    <div class="body">大屏整月排班待实现（M2）</div>
  </div>
</template>

<script setup>
import { onMounted, onUnmounted, ref } from 'vue'

const now = ref('')
let timer
const tick = () => { now.value = new Date().toLocaleString('zh-CN', { hour12: false }) }
onMounted(() => { tick(); timer = setInterval(tick, 1000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.screen { position: fixed; inset: 0; background: #0b1220; color: #e5e7eb; padding: 2.2vh 2.2vw; display: flex; flex-direction: column; }
.head { display: flex; justify-content: space-between; align-items: flex-end; }
.title { font-size: 4.2vh; font-weight: 700; letter-spacing: .1em; color: #fff; }
.clock { font-size: 3vh; font-variant-numeric: tabular-nums; }
.body { flex: 1; display: flex; align-items: center; justify-content: center; font-size: 3vh; color: #64748b; }
</style>
