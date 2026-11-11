<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import {
  ArrowRight,
  ArrowUpRight,
  LockKeyhole,
  Link2,
  Inbox,
  Bird,
  Loader2,
} from "lucide-vue-next";
import Brand from "../components/Brand.vue";
import { api, message, toast } from "../api";
const router = useRouter();
const code = ref("");
const busy = ref(false);
async function resolve() {
  busy.value = true;
  try {
    const result = await api<{ path: string }>("/public/resolve", "POST", {
      code: code.value,
    });
    await router.push(result.path);
  } catch (error) {
    toast(message(error), "error");
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <div class="public-shell">
    <header class="public-header">
      <Brand /><RouterLink to="/admin" class="button subtle"
        >管理文件<ArrowUpRight :size="16"
      /></RouterLink>
    </header>
    <main class="home-main">
      <div class="home-intro">
        <span class="eyebrow"
          ><span class="status-dot"></span> 你的个人文件中转站</span
        >
        <h1>文件传递，<br /><span>轻轻松松。</span></h1>
        <p>
          分享一个链接，把文件送到对方手中。<br />也可以创建收件箱，等文件飞过来。
        </p>
      </div>
      <section class="pickup-card">
        <div class="pickup-icon"><Bird :size="37" :stroke-width="1.5" /></div>
        <h2>有文件等你签收</h2>
        <p>输入收到的取件码，打开分享或收件箱。</p>
        <form @submit.prevent="resolve">
          <label for="pickup-code">取件码</label
          ><input
            id="pickup-code"
            v-model="code"
            class="code-input"
            placeholder="输入 10 位取件码"
            maxlength="16"
            autocomplete="off"
            spellcheck="false"
            required
          /><button
            class="button primary wide"
            :disabled="busy || !code.trim()"
          >
            <Loader2 v-if="busy" class="spin" :size="18" /><span
              >打开文件链接</span
            ><ArrowRight :size="18" />
          </button>
        </form>
        <div class="pickup-footnote">
          <LockKeyhole :size="14" />有访问密码的链接，取件时需要验证
        </div>
      </section>
      <div class="home-features">
        <div>
          <Link2 :size="20" /><span
            ><strong>发文件</strong><small>上传后生成专属分享链接</small></span
          >
        </div>
        <div>
          <Inbox :size="20" /><span
            ><strong>收文件</strong><small>对方打开链接就能上传</small></span
          >
        </div>
      </div>
    </main>
    <footer class="public-footer">
      <span>GuGuPan · 咕咕盘</span><span>文件有归处，传递更简单。</span>
    </footer>
  </div>
</template>
