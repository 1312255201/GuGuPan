<script setup lang="ts">
import { ref, onMounted, computed, watch } from "vue";
import { useRoute } from "vue-router";
import {
  Download,
  Inbox,
  Link2,
  LockKeyhole,
  Loader2,
  Clock3,
  AlertCircle,
  ArrowLeft,
  RefreshCw,
} from "lucide-vue-next";
import Brand from "../components/Brand.vue";
import FileGlyph from "../components/FileGlyph.vue";
import UploadBox from "../components/UploadBox.vue";
import { api, message, bytes, date, toast, type PublicLink } from "../api";
const route = useRoute();
const data = ref<PublicLink>();
const password = ref("");
const error = ref("");
const busy = ref(false);
const loading = ref(true);
const token = computed(() => String(route.params.token));
const remaining = computed(() =>
  data.value?.usage
    ? Math.max(0, data.value.link.quotaBytes - data.value.usage.usedBytes)
    : 0,
);
const full = computed(
  () =>
    !!data.value?.usage &&
    (data.value.usage.fileCount >= data.value.link.maxFiles ||
      remaining.value <= 0),
);
async function load() {
  error.value = "";
  try {
    data.value = await api<PublicLink>(`/public/links/${token.value}`);
  } catch (failure) {
    error.value = message(failure);
    data.value = undefined;
  } finally {
    loading.value = false;
  }
}
async function unlock() {
  busy.value = true;
  try {
    await api(`/public/links/${token.value}/unlock`, "POST", {
      password: password.value,
    });
    await load();
  } catch (failure) {
    toast(message(failure), "error");
  } finally {
    busy.value = false;
  }
}
async function completed() {
  toast("文件已送达");
  await load();
}
onMounted(load);
watch(token, () => {
  data.value = undefined;
  password.value = "";
  loading.value = true;
  void load();
});
</script>
<template>
  <div class="public-shell">
    <header class="public-header">
      <Brand /><RouterLink to="/" class="text-button"
        ><ArrowLeft :size="16" />取件首页</RouterLink
      >
    </header>
    <main class="transfer-main">
      <div v-if="loading" class="loading-state">
        <Loader2 class="spin" :size="27" />正在打开链接…
      </div>
      <section v-else-if="error" class="transfer-card error-state">
        <AlertCircle :size="39" :stroke-width="1.3" />
        <h1>{{ error }}</h1>
        <p>请联系文件发送者获取新的链接。</p>
        <button class="button secondary" @click="load">
          <RefreshCw :size="16" />重新检查
        </button>
      </section>
      <section v-else-if="data" class="transfer-card">
        <span class="transfer-icon"
          ><LockKeyhole v-if="data.locked" :size="29" /><Inbox
            v-else-if="data.link.kind === 'INBOX'"
            :size="29" /><Link2 v-else :size="29" /></span
        ><span class="eyebrow">{{
          data.link.kind === "INBOX" ? "文件收件箱" : "有人给你分享了文件"
        }}</span>
        <h1>{{ data.link.title }}</h1>
        <p v-if="data.link.note" class="transfer-note">{{ data.link.note }}</p>
        <div class="transfer-expiry">
          <Clock3 :size="14" />{{ date(data.link.expiresAt) }} 到期
        </div>
        <form
          v-if="data.locked"
          class="form-stack unlock-form"
          @submit.prevent="unlock"
        >
          <label
            >访问密码<input
              v-model="password"
              type="password"
              autocomplete="off"
              placeholder="输入发送者提供的密码"
              maxlength="128"
              required /></label
          ><button class="button primary wide" :disabled="busy">
            <Loader2 v-if="busy" :size="18" class="spin" />验证并打开
          </button>
        </form>
        <template v-else-if="data.link.kind === 'SHARE'"
          ><div v-if="data.files.length" class="public-file-list">
            <div
              v-for="file in data.files"
              :key="file.id"
              class="public-file-row"
            >
              <FileGlyph :name="file.name" />
              <div>
                <strong>{{ file.name }}</strong
                ><small
                  >{{ bytes(file.size) }} · 文件保留至
                  {{ date(file.expiresAt) }}</small
                >
              </div>
              <a
                class="button secondary small"
                :href="`/api/public/links/${token}/files/${file.id}`"
                :aria-label="`下载 ${file.name}`"
                title="下载文件"
                ><Download :size="16" /><span>下载</span></a
              >
            </div>
          </div>
          <div v-else class="empty-state">
            <Inbox :size="30" />
            <h3>文件已过期或已被删除</h3>
            <p>请联系发送者重新分享</p>
          </div></template
        ><template v-else
          ><p class="inbox-instruction">
            选择需要发送的文件，上传完成后对方即可收到。
          </p>
          <div v-if="full" class="inline-error">
            此收件箱已满，请联系收件人。
          </div>
          <UploadBox
            :endpoint="`/public/links/${token}/files`"
            :max-bytes="
              Math.min(
                data.link.maxFileBytes,
                data.systemMaxFileBytes || data.link.maxFileBytes,
                remaining,
              )
            "
            :disabled="full"
            @complete="completed"
          />
          <p class="form-help">
            收件箱剩余 {{ bytes(remaining) }}，还可接收
            {{
              Math.max(0, data.link.maxFiles - (data.usage?.fileCount || 0))
            }}
            个文件。上传的文件仅收件人可查看。
          </p></template
        >
      </section>
    </main>
    <footer class="public-footer">
      <span>由 GuGuPan · 咕咕盘传递</span><span>文件有归处，传递更简单。</span>
    </footer>
  </div>
</template>
