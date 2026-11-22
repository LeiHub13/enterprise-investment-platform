<script setup lang="ts">
import { computed } from 'vue'
import { Lock, User } from '@element-plus/icons-vue'

const props = defineProps<{
  busy: boolean
  error: string
  username: string
  password: string
  remember: boolean
}>()
const emit = defineEmits<{
  'update:username': [value: string]
  'update:password': [value: string]
  'update:remember': [value: boolean]
  submit: []
}>()

const username = computed({
  get: () => props.username,
  set: value => emit('update:username', value),
})
const password = computed({
  get: () => props.password,
  set: value => emit('update:password', value),
})
const remember = computed({
  get: () => props.remember,
  set: value => emit('update:remember', value),
})
</script>

<template>
  <div class="login-page">
    <section class="login-hero">
      <div class="hero-grid" aria-hidden="true" />
      <div class="hero-glow hero-glow-a" aria-hidden="true" />
      <div class="hero-glow hero-glow-b" aria-hidden="true" />

      <div class="hero-brand">
        <span class="hero-logo">招</span>
        <div>
          <strong>企业招商平台</strong>
          <small>INVESTMENT PLATFORM</small>
        </div>
      </div>

      <div class="hero-copy">
        <p class="hero-eyebrow">ENTERPRISE RESOURCE &amp; INVESTMENT</p>
        <h1>全国企业资源<br />与招商管理平台</h1>
        <p class="hero-description">
          统一维护企业档案、招商项目与线索跟进，让每一次招商行动都有据可循。
        </p>
        <ul class="hero-features">
          <li><span>01</span><div><strong>企业资源统一归档</strong><small>按地区、行业与经营状态快速检索</small></div></li>
          <li><span>02</span><div><strong>项目与线索协同</strong><small>项目、线索、跟进记录一体化管理</small></div></li>
          <li><span>03</span><div><strong>数据变更可追溯</strong><small>导入批次、字段变更与操作审计留痕</small></div></li>
        </ul>
      </div>

      <footer class="hero-footer">内部工作台 · 数据仅限内部使用</footer>
    </section>

    <section class="login-panel-wrap">
      <div class="login-panel">
        <div class="login-panel-brand">
          <span class="hero-logo">招</span>
          <strong>企业招商平台</strong>
        </div>
        <p class="login-eyebrow">招商工作台</p>
        <h2>欢迎登录</h2>
        <p class="login-subtitle">使用内部账号进入企业资源与招商管理平台</p>

        <el-form label-position="top" size="large" @submit.prevent="emit('submit')">
          <el-form-item label="账号">
            <el-input
              v-model="username"
              :prefix-icon="User"
              placeholder="请输入用户名"
              autocomplete="username"
              maxlength="64"
            />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="password"
              :prefix-icon="Lock"
              type="password"
              show-password
              placeholder="请输入密码"
              autocomplete="current-password"
              @keyup.enter="emit('submit')"
            />
          </el-form-item>
          <div class="login-options">
            <el-checkbox v-model="remember">记住账号</el-checkbox>
            <span class="login-hint">忘记密码请联系管理员</span>
          </div>
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="login-error" />
          <el-button type="primary" native-type="submit" :loading="busy" class="login-submit">
            登 录
          </el-button>
        </el-form>

        <p class="login-footer">© 2026 企业招商平台 · 内部系统</p>
      </div>
    </section>
  </div>
</template>

<style scoped>
.login-page {
  display: grid;
  grid-template-columns: minmax(0, 1.05fr) minmax(420px, .95fr);
  min-height: 100vh;
  background: #f5f7f9;
}

.login-hero {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  overflow: hidden;
  padding: clamp(34px, 5vw, 70px);
  color: #fff;
  background:
    radial-gradient(circle at 82% 18%, rgb(64 158 255 / 24%), transparent 34%),
    radial-gradient(circle at 12% 88%, rgb(54 207 201 / 16%), transparent 32%),
    linear-gradient(145deg, #1b2a3a 0%, #20364d 52%, #162334 100%);
}

.hero-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgb(255 255 255 / 4%) 1px, transparent 1px),
    linear-gradient(90deg, rgb(255 255 255 / 4%) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: linear-gradient(180deg, rgb(0 0 0 / 85%), transparent 78%);
}

.hero-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(2px);
  pointer-events: none;
}

.hero-glow-a {
  top: 12%;
  right: -80px;
  width: 300px;
  height: 300px;
  border: 1px solid rgb(64 158 255 / 30%);
  box-shadow: 0 0 80px rgb(64 158 255 / 18%) inset;
}

.hero-glow-b {
  bottom: 8%;
  left: -100px;
  width: 360px;
  height: 360px;
  border: 1px solid rgb(54 207 201 / 20%);
}

.hero-brand,
.hero-copy,
.hero-footer {
  position: relative;
  z-index: 1;
}

.hero-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hero-logo {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 10px;
  background: linear-gradient(135deg, #409eff, #79bbff);
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  box-shadow: 0 8px 22px rgb(64 158 255 / 35%);
}

.hero-brand strong,
.hero-brand small {
  display: block;
}

.hero-brand strong {
  font-size: 17px;
  letter-spacing: .02em;
}

.hero-brand small {
  margin-top: 3px;
  color: rgb(255 255 255 / 45%);
  font-size: 9px;
  letter-spacing: .18em;
}

.hero-copy {
  max-width: 600px;
  margin: 7vh 0;
}

.hero-eyebrow {
  margin: 0 0 18px;
  color: #79bbff;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: .18em;
}

.hero-copy h1 {
  margin: 0;
  font-size: clamp(32px, 3.6vw, 52px);
  font-weight: 700;
  line-height: 1.28;
  letter-spacing: .01em;
}

.hero-description {
  max-width: 460px;
  margin: 20px 0 0;
  color: rgb(255 255 255 / 62%);
  font-size: 14px;
  line-height: 1.9;
}

.hero-features {
  display: grid;
  gap: 18px;
  margin: 40px 0 0;
  padding: 0;
  list-style: none;
}

.hero-features li {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}

.hero-features li > span {
  display: grid;
  flex: 0 0 auto;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid rgb(121 187 255 / 35%);
  border-radius: 8px;
  color: #79bbff;
  font-size: 11px;
  font-weight: 700;
  background: rgb(64 158 255 / 8%);
}

.hero-features strong,
.hero-features small {
  display: block;
}

.hero-features strong {
  color: rgb(255 255 255 / 90%);
  font-size: 13px;
  font-weight: 600;
}

.hero-features small {
  margin-top: 4px;
  color: rgb(255 255 255 / 45%);
  font-size: 12px;
}

.hero-footer {
  color: rgb(255 255 255 / 35%);
  font-size: 11px;
  letter-spacing: .06em;
}

.login-panel-wrap {
  display: grid;
  place-items: center;
  padding: 40px 24px;
  background:
    radial-gradient(circle at 100% 0%, rgb(64 158 255 / 7%), transparent 32%),
    #f5f7f9;
}

.login-panel {
  width: min(420px, 100%);
  padding: 38px 38px 30px;
  border: 1px solid rgb(0 21 41 / 6%);
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 18px 50px rgb(0 21 41 / 8%);
}

.login-panel-brand {
  display: none;
  align-items: center;
  gap: 10px;
  margin-bottom: 24px;
}

.login-panel-brand .hero-logo {
  width: 36px;
  height: 36px;
  font-size: 18px;
}

.login-panel-brand strong {
  color: #303133;
  font-size: 15px;
}

.login-eyebrow {
  margin: 0 0 8px;
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .16em;
}

.login-panel h2 {
  margin: 0;
  color: #1f2d3d;
  font-size: 27px;
  font-weight: 700;
  letter-spacing: .01em;
}

.login-subtitle {
  margin: 9px 0 26px;
  color: #8a97a5;
  font-size: 13px;
  line-height: 1.7;
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin: -4px 0 18px;
}

.login-hint {
  color: #a0aab5;
  font-size: 12px;
}

.login-error {
  margin-bottom: 14px;
}

.login-submit {
  width: 100%;
  height: 44px;
  margin-top: 2px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: .12em;
  box-shadow: 0 8px 18px rgb(64 158 255 / 24%);
}

.login-footer {
  margin: 26px 0 0;
  color: #b3bcc5;
  font-size: 11px;
  text-align: center;
}

@media (max-width: 960px) {
  .login-page {
    display: block;
    min-height: 100vh;
  }

  .login-hero {
    display: none;
  }

  .login-panel-wrap {
    min-height: 100vh;
    padding: 24px 16px;
  }

  .login-panel-brand {
    display: flex;
  }

  .login-panel {
    padding: 30px 24px 24px;
  }
}
</style>
