<script setup lang="ts">
import { ref } from "vue";
import {
  Plus,
  Copy,
  ExternalLink,
  FileText,
  Clock3,
  LockKeyhole,
  Loader2,
} from "lucide-vue-next";
import Modal from "./Modal.vue";
import {
  api,
  copy,
  linkUrl,
  date,
  message,
  toast,
  type LinkItem,
} from "../api";
defineProps<{ links: LinkItem[] }>();
const emit = defineEmits<{
  created: [link: LinkItem, password: string];
  changed: [];
}>();
const editing = ref(false);
const title = ref("");
const content = ref("");
const password = ref("");
const days = ref(3);
const saving = ref(false);
const error = ref("");
function open() {
  title.value = "";
  content.value = "";
  password.value = "";
  error.value = "";
  editing.value = true;
}
async function save() {
  saving.value = true;
  error.value = "";
  try {
    const link = await api<LinkItem>("/admin/texts", "POST", {
      title: title.value,
      content: content.value,
      password: password.value,
      days: days.value,
    });
    editing.value = false;
    emit("created", link, password.value);
  } catch (failure) {
    error.value = message(failure);
  } finally {
    saving.value = false;
  }
}
async function close(link: LinkItem) {
  if (!window.confirm(`关闭“${link.title}”？链接与取件码将立即失效。`)) return;
  try {
    await api(`/admin/links/${link.token}`, "DELETE");
    emit("changed");
    toast("文本链接已关闭");
  } catch (failure) {
    toast(message(failure), "error");
  }
}
</script>
<template>
  <div class="links-toolbar">
    <span>{{ links.length }} 份文本</span
    ><button class="button primary" @click="open">
      <Plus :size="17" />新建文本
    </button>
  </div>
  <div v-if="links.length" class="links-grid">
    <article v-for="link in links" :key="link.token" class="link-card">
      <div class="link-card-top">
        <span class="link-card-icon"><FileText :size="23" /></span
        ><span
          class="state-pill"
          :class="{ inactive: link.revoked || link.expiresAt <= Date.now() }"
          >{{
            link.revoked
              ? "已关闭"
              : link.expiresAt <= Date.now()
                ? "已过期"
                : "使用中"
          }}</span
        >
      </div>
      <h2>{{ link.title }}</h2>
      <div class="link-meta">
        <span><Clock3 :size="14" />{{ date(link.expiresAt) }} 到期</span
        ><span v-if="link.passwordProtected"
          ><LockKeyhole :size="14" />密码保护</span
        >
      </div>
      <div class="code-row">
        <span>取件码</span><strong>{{ link.code }}</strong
        ><button
          class="icon-button"
          aria-label="复制取件码"
          @click="copy(link.code)"
        >
          <Copy :size="15" />
        </button>
      </div>
      <div class="link-card-footer">
        <a
          :href="linkUrl(link)"
          target="_blank"
          rel="noopener"
          class="text-button"
          ><ExternalLink :size="15" />打开文本</a
        ><button
          class="text-button"
          @click="copy(`${link.title}\n${linkUrl(link)}\n取件码：${link.code}`)"
        >
          复制链接</button
        ><button
          v-if="!link.revoked && link.expiresAt > Date.now()"
          class="text-button"
          @click="close(link)"
        >
          关闭
        </button>
      </div>
    </article>
  </div>
  <div v-else class="empty-state empty-panel">
    <FileText :size="36" />
    <h3>在设备之间传递一段文字</h3>
    <p>粘贴网址、代码或文字，通过链接或取件码取用</p>
    <button class="button secondary" @click="open">新建文本</button>
  </div>
  <Modal
    v-if="editing"
    title="新建文本中转"
    @close="!saving && (editing = false)"
  >
    <form class="form-stack" @submit.prevent="save">
      <label
        >名称<input
          v-model="title"
          required
          maxlength="120"
          placeholder="例如：待用链接"
      /></label>
      <label
        >文本内容<textarea
          v-model="content"
          required
          maxlength="100000"
          rows="10"
          spellcheck="false"
          placeholder="粘贴需要中转的文字或代码"
        />
      </label>
      <p class="form-help">
        {{ content.length }} / 100000 字符，支持复制全文和下载 TXT。
      </p>
      <div class="form-row">
        <label
          >有效期<select v-model.number="days">
            <option :value="1">1 天</option>
            <option :value="3">3 天</option>
            <option :value="7">7 天</option>
            <option :value="30">30 天</option>
          </select></label
        ><label
          >访问密码<input
            v-model="password"
            minlength="4"
            maxlength="64"
            placeholder="选填，至少 4 个字符"
            autocomplete="off"
        /></label>
      </div>
      <p v-if="error" role="alert" class="inline-error">{{ error }}</p>
      <button class="button primary wide" :disabled="saving">
        <Loader2 v-if="saving" class="spin" :size="17" />生成文本链接
      </button>
    </form>
  </Modal>
</template>
