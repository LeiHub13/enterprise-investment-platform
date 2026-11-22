<script setup lang="ts">
import { computed } from 'vue'
import type { Value } from './api'
import { regionName, regionOptions } from './regions'
import type { RegionLevel } from './regions'

const props = defineProps<{
  modelValue?: Value
  level: RegionLevel
  parent?: Value
  placeholder?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: Value] }>()
const options = computed(() => {
  const available = regionOptions(props.level, props.parent).map(region => ({
    value: props.level === 'district' ? region.code : region.name,
    label: region.name,
  }))
  const current = props.modelValue
  if (typeof current === 'string' && current && !available.some(region => region.value === current)) {
    return [{ value: current, label: regionName(current, props.level, props.parent) }, ...available]
  }
  return available
})
</script>

<template>
  <el-select :model-value="modelValue" :placeholder="placeholder || '请选择地区'"
    filterable allow-create default-first-option clearable :value-on-clear="null"
    @update:model-value="(value: Value) => emit('update:modelValue', value ?? null)">
    <el-option v-for="region in options" :key="region.value" :label="region.label" :value="region.value" />
  </el-select>
</template>
