<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ArrowLeft, ArrowRight, Loader2, LockKeyhole } from "lucide-vue-next";
import Brand from "../components/Brand.vue";
import { api, refreshSession, message } from "../api";
const router = useRouter();
const username = ref("admin");
const password = ref("");
const busy = ref(false);
const error = ref("");
async function login() {
  busy.value = true;
  error.value = "";
  try {
    await api(
      "/auth/login",
      "POST",
      new URLSearchParams({
        username: username.value,
        password: password.value,
      }),
    );
    await refreshSession();
    await router.replace("/admin");
  } catch (failure) {
    error.value = message(failure);
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="public-shell">
    <header class="public-header">
      <Brand /><RouterLink to="/" class="text-button"
        ><ArrowLeft :size="16" />返回首页</RouterLink
      >
    </header>
    <main class="login-main">
      <section class="login-card">
        <span class="login-lock"><LockKeyhole :size="26" /></span>
        <h1>欢迎回到咕咕盘</h1>
        <p class="muted">登录后管理文件、分享和收件箱</p>
        <form class="form-stack" @submit.prevent="login">
          <label
            >管理员账号<input
              v-model="username"
              autocomplete="username"
              maxlength="64"
              required /></label
          ><label
            >密码<input
              v-model="password"
              type="password"
              autocomplete="current-password"
              maxlength="128"
              required
          /></label>
          <p v-if="error" class="inline-error" role="alert">{{ error }}</p>
          <button class="button primary wide" :disabled="busy">
            <Loader2 v-if="busy" :size="18" class="spin" />登录<ArrowRight
              :size="17"
            />
          </button>
        </form>
        <p class="login-help">
          首次登录密码见服务器数据目录中的<br /><code
            >initial-admin-password.txt</code
          >
        </p>
      </section>
    </main>
    <footer class="public-footer">
      <span>GuGuPan · 咕咕盘</span><span>属于你的小小文件驿站</span>
    </footer>
  </div>
</template>
