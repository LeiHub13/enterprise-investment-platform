<script setup lang="ts">
import type { Value } from './api'
import type { Field, Option } from './sections'

const model = defineModel<Record<string, Value>>({ required: true })
defineProps<{
  fields: Field[]
  choices?: Record<string, Option[]>
  loadingChoice?: string
}>()
const emit = defineEmits<{ search: [field: Field, keyword: string] }>()

function isRemote(field: Field) {
  return field.kind === 'enterprise' || field.kind === 'project'
}

function update(field: Field, value: Value | undefined) {
  model.value[field.key] = value ?? null
}

function numeric(value: Value) {
  return value == null || value === '' ? undefined : Number(value)
}

function dateValue(value: Value) {
  return typeof value === 'string' ? value : ''
}
</script>

<template>
  <div class="form-grid">
    <el-form-item
      v-for="field in fields"
      :key="field.key"
      :label="field.label"
      :required="field.required"
      :class="{ wide: field.kind === 'text' }"
    >
      <el-select
        v-if="field.kind === 'select' || field.kind === 'user' || isRemote(field)"
        :model-value="model[field.key]"
        :placeholder="isRemote(field) ? '输入名称搜索' : '请选择'"
        :filterable="isRemote(field) || field.kind === 'user'"
        :remote="isRemote(field)"
        :remote-method="(keyword: string) => emit('search', field, keyword)"
        :loading="loadingChoice === field.key"
        :clearable="!field.required"
        :value-on-clear="null"
        @visible-change="(visible: boolean) => visible && isRemote(field) && emit('search', field, '')"
        @update:model-value="(value: Value) => update(field, value)"
      >
        <el-option v-for="option in choices?.[field.key] ?? field.options ?? []" :key="option.value" v-bind="option" />
      </el-select>
      <el-input-number
        v-else-if="field.kind === 'number'"
        :model-value="numeric(model[field.key])"
        :min="field.min"
        :max="field.max"
        :precision="2"
        :controls="false"
        @update:model-value="(value: number | null | undefined) => update(field, value)"
      />
      <el-date-picker
        v-else-if="field.kind === 'date' || field.kind === 'datetime'"
        :model-value="dateValue(model[field.key])"
        :type="field.kind"
        :value-format="field.kind === 'date' ? 'YYYY-MM-DD' : 'YYYY-MM-DDTHH:mm:ss'"
        :placeholder="field.kind === 'date' ? '选择日期' : '选择日期和时间'"
        @update:model-value="(value: unknown) => update(field, typeof value === 'string' ? value : null)"
      />
      <el-input
        v-else
        :model-value="model[field.key] ?? ''"
        :type="field.kind === 'text' ? 'textarea' : field.kind === 'password' ? 'password' : 'text'"
        :rows="3"
        :placeholder="field.placeholder"
        :maxlength="field.max"
        :inputmode="field.kind === 'decimal' ? 'decimal' : undefined"
        :show-password="field.kind === 'password'"
        :autocomplete="field.kind === 'password' ? 'new-password' : 'off'"
        @update:model-value="(value: string) => update(field, value)"
      />
    </el-form-item>
  </div>
</template>
