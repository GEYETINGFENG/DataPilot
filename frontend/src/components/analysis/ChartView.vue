<script setup lang="ts">
import { onBeforeUnmount, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps<{ option: Record<string, unknown> }>()

const container = useTemplateRef<HTMLDivElement>('container')
const chart = shallowRef<echarts.ECharts>()
let observer: ResizeObserver | undefined

onMounted(() => {
  if (!container.value) return
  chart.value = echarts.init(container.value)
  chart.value.setOption(props.option)
  observer = new ResizeObserver(() => chart.value?.resize())
  observer.observe(container.value)
})

watch(
  () => props.option,
  (option) => chart.value?.setOption(option, true),
)

onBeforeUnmount(() => {
  observer?.disconnect()
  chart.value?.dispose()
})
</script>

<template>
  <div ref="container" class="chart"></div>
</template>

<style scoped>
.chart {
  width: 100%;
  height: 300px;
}
</style>
