<script setup lang="ts">
import { computed, ref, watch, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  FolderOpen,
  FileText,
  Link2,
  Inbox,
  Settings2,
  LogOut,
  Plus,
  Search,
  Upload,
  Download,
  Trash2,
  Copy,
  Clock3,
  ArrowUpRight,
  HardDrive,
  Files,
  ShieldCheck,
  Loader2,
  RefreshCw,
  LockKeyhole,
  QrCode,
  Check,
  X,
  CalendarPlus,
} from "lucide-vue-next";
import QRCode from "qrcode";
import Brand from "../components/Brand.vue";
import FileGlyph from "../components/FileGlyph.vue";
import UploadBox from "../components/UploadBox.vue";
import Modal from "../components/Modal.vue";
import TextPanel from "../components/TextPanel.vue";
import {
  api,
  auth,
  refreshSession,
  toast,
  message,
  bytes,
  date,
  expiry,
  linkUrl,
  copy,
  type FileItem,
  type LinkItem,
  type Stats,
} from "../api";

const route = useRoute();
const router = useRouter();
const gib = 1024 ** 3;
const allFiles = ref<FileItem[]>([]);
const allLinks = ref<LinkItem[]>([]);
const stats = ref<Stats>();
const loading = ref(true);
const loadError = ref("");
const query = ref("");
const source = ref("all");
const selected = ref<string[]>([]);
const page = ref(1);
const archiveQuery = computed(() =>
  new URLSearchParams({ ids: selected.value.join(",") }).toString(),
);
const uploadBox = ref<InstanceType<typeof UploadBox>>();
const modal = ref<"share" | "inbox" | "result" | "">("");
const saving = ref(false);
const formError = ref("");
const title = ref("");
const note = ref("");
const days = ref(3);
const password = ref("");
const inboxQuota = ref(2);
const inboxMaxFile = ref(1);
const inboxMaxFiles = ref(20);
const result = ref<LinkItem>();
const resultPassword = ref("");
const qr = ref("");
const settingsQuota = ref(20);
const settingsMaxFile = ref(1);
const settingsRetention = ref(7);
const currentPassword = ref("");
const newPassword = ref("");
const repeatPassword = ref("");

const tab = computed(() =>
  ["shares", "inboxes", "texts", "settings"].includes(String(route.params.tab))
    ? String(route.params.tab)
    : "files",
);
const heading = computed(
  () =>
    ({
      files: "我的文件",
      shares: "分享链接",
      texts: "文本中转",
      inboxes: "收件箱",
      settings: "系统设置",
    })[tab.value],
);
const subheading = computed(
  () =>
    ({
      files: "随时取用，也能轻松分享。",
      shares: "管理你发出的每一份文件。",
      texts: "把文字和链接送到另一台设备。",
      inboxes: "创建一个链接，让文件向你飞来。",
      settings: "管理存储空间和管理员账号。",
    })[tab.value],
);
const active = (link: LinkItem) => !link.revoked && link.expiresAt > Date.now();
const activeShares = computed(
  () =>
    allLinks.value.filter((link) => link.kind === "SHARE" && active(link))
      .length,
);
const filteredFiles = computed(() =>
  allFiles.value.filter(
    (file) =>
      file.name.toLowerCase().includes(query.value.toLowerCase()) &&
      (source.value === "all" ||
        (source.value === "received"
          ? file.inboxToken !== null
          : file.inboxToken === null)),
  ),
);
const pageCount = computed(() =>
  Math.max(1, Math.ceil(filteredFiles.value.length / 15)),
);
const pageFiles = computed(() =>
  filteredFiles.value.slice((page.value - 1) * 15, page.value * 15),
);
const shareableFiles = computed(() =>
  pageFiles.value.filter((file) => file.expiresAt > Date.now()),
);
const allSelected = computed(
  () =>
    shareableFiles.value.length > 0 &&
    shareableFiles.value.every((file) => selected.value.includes(file.id)),
);
const visibleLinks = computed(() =>
  allLinks.value.filter(
    (link) =>
      link.kind === (tab.value === "shares" ? "SHARE" : "INBOX") &&
      link.title.toLowerCase().includes(query.value.toLowerCase()),
  ),
);
const usedPercent = computed(() =>
  stats.value
    ? Math.min(
        100,
        (stats.value.usedBytes / stats.value.settings.quotaBytes) * 100,
      )
    : 0,
);
const inboxNames = computed(
  () =>
    new Map(
      allLinks.value
        .filter((link) => link.kind === "INBOX")
        .map((link) => [link.token, link.title]),
    ),
);
watch([query, source], () => {
  page.value = 1;
});
watch(pageCount, (count) => {
  page.value = Math.min(page.value, count);
});
watch(tab, () => {
  query.value = "";
  selected.value = [];
  page.value = 1;
});
watch(
  () => auth.authenticated,
  (loggedIn) => {
    if (!loggedIn) void router.replace("/login");
  },
);

async function load() {
  loadError.value = "";
  try {
    const [files, links, summary] = await Promise.all([
      api<FileItem[]>("/admin/files"),
      api<LinkItem[]>("/admin/links"),
      api<Stats>("/admin/stats"),
    ]);
    allFiles.value = files;
    allLinks.value = links;
    stats.value = summary;
    selected.value = selected.value.filter((id) =>
      files.some((file) => file.id === id && file.expiresAt > Date.now()),
    );
  } catch (error) {
    loadError.value = message(error);
  } finally {
    loading.value = false;
  }
}
onMounted(async () => {
  await load();
  if (stats.value) {
    settingsQuota.value = stats.value.settings.quotaBytes / gib;
    settingsMaxFile.value = stats.value.settings.maxFileBytes / gib;
    settingsRetention.value = stats.value.settings.retentionDays;
  }
});
function toggleAll() {
  const ids = shareableFiles.value.map((file) => file.id);
  selected.value = allSelected.value
    ? selected.value.filter((id) => !ids.includes(id))
    : [...new Set([...selected.value, ...ids])];
}
function openCreate(kind: "share" | "inbox", file?: FileItem) {
  if (file) selected.value = [file.id];
  title.value =
    kind === "share"
      ? allFiles.value.find((item) => selected.value.includes(item.id))?.name ||
        "文件分享"
      : "";
  note.value = "";
  password.value = "";
  days.value = kind === "share" ? 3 : 1;
  formError.value = "";
  inboxQuota.value = Math.min(
    2,
    stats.value?.settings.quotaBytes
      ? stats.value.settings.quotaBytes / gib
      : 2,
  );
  inboxMaxFile.value = Math.min(
    1,
    stats.value?.settings.maxFileBytes
      ? stats.value.settings.maxFileBytes / gib
      : 1,
    inboxQuota.value,
  );
  modal.value = kind;
}
async function createLink() {
  saving.value = true;
  formError.value = "";
  try {
    const payload = {
      title: title.value,
      note: note.value,
      password: password.value,
      days: days.value,
    };
    result.value =
      modal.value === "share"
        ? await api<LinkItem>("/admin/shares", "POST", {
            ...payload,
            fileIds: selected.value,
          })
        : await api<LinkItem>("/admin/inboxes", "POST", {
            ...payload,
            quotaBytes: Math.round(inboxQuota.value * gib),
            maxFileBytes: Math.round(inboxMaxFile.value * gib),
            maxFiles: inboxMaxFiles.value,
          });
    resultPassword.value = password.value;
    await showResult(result.value, true);
    await load();
  } catch (error) {
    formError.value = message(error);
  } finally {
    saving.value = false;
  }
}
async function textCreated(link: LinkItem, password: string) {
  resultPassword.value = password;
  await showResult(link, true);
  await load();
}
async function showResult(link: LinkItem, justCreated = false) {
  result.value = link;
  modal.value = "result";
  qr.value = "";
  if (!justCreated) resultPassword.value = "";
  try {
    qr.value = await QRCode.toDataURL(linkUrl(link), {
      width: 200,
      margin: 1,
      color: { dark: "#244f45", light: "#ffffff" },
    });
  } catch {
    /* The selectable link is still available. */
  }
}
function copyShare(link: LinkItem) {
  const text = `${link.title}\n${linkUrl(link)}\n取件码：${link.code}${resultPassword.value && result.value?.token === link.token ? `\n访问密码：${resultPassword.value}` : ""}`;
  void copy(text);
}
async function removeFile(file: FileItem) {
  if (!window.confirm(`删除“${file.name}”？相关分享也将无法下载该文件。`))
    return;
  try {
    await api(`/admin/files/${file.id}`, "DELETE");
    toast("文件已删除");
    await load();
  } catch (error) {
    toast(message(error), "error");
  }
}
async function extend(file: FileItem) {
  try {
    await api(`/admin/files/${file.id}`, "PATCH", {
      days: stats.value?.settings.retentionDays || 7,
    });
    toast("已延长文件保留时间");
    await load();
  } catch (error) {
    toast(message(error), "error");
  }
}
async function revoke(link: LinkItem) {
  if (!window.confirm(`关闭“${link.title}”？链接和取件码会立即失效。`)) return;
  try {
    await api(`/admin/links/${link.token}`, "DELETE");
    toast("链接已关闭");
    await load();
  } catch (error) {
    toast(message(error), "error");
  }
}
async function logout() {
  try {
    await api("/auth/logout", "POST");
    await refreshSession();
    await router.push("/");
  } catch (error) {
    toast(message(error), "error");
  }
}
async function saveSettings() {
  saving.value = true;
  try {
    await api("/admin/settings", "PUT", {
      quotaBytes: Math.round(settingsQuota.value * gib),
      maxFileBytes: Math.round(settingsMaxFile.value * gib),
      retentionDays: settingsRetention.value,
    });
    toast("设置已保存");
    await load();
  } catch (error) {
    toast(message(error), "error");
  } finally {
    saving.value = false;
  }
}
async function changePassword() {
  if (newPassword.value !== repeatPassword.value) {
    toast("两次输入的新密码不一致", "error");
    return;
  }
  saving.value = true;
  try {
    await api("/admin/password", "POST", {
      currentPassword: currentPassword.value,
      newPassword: newPassword.value,
    });
    toast("密码已更新，请重新登录");
    auth.authenticated = false;
    await router.replace("/login");
  } catch (error) {
    toast(message(error), "error");
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <div class="admin-shell">
    <aside class="sidebar">
      <Brand />
      <div class="sidebar-caption">工作空间</div>
      <nav class="sidebar-nav" aria-label="文件管理">
        <RouterLink to="/admin" :class="{ active: tab === 'files' }"
          ><FolderOpen :size="20" />我的文件<span
            v-if="stats"
            class="nav-count"
            >{{ stats.fileCount }}</span
          ></RouterLink
        >
        <RouterLink to="/admin/shares" :class="{ active: tab === 'shares' }"
          ><Link2 :size="20" />分享链接</RouterLink
        >
        <RouterLink to="/admin/texts" :class="{ active: tab === 'texts' }"
          ><FileText :size="20" />文本中转</RouterLink
        >
        <RouterLink to="/admin/inboxes" :class="{ active: tab === 'inboxes' }"
          ><Inbox :size="20" />收件箱</RouterLink
        >
        <RouterLink to="/admin/settings" :class="{ active: tab === 'settings' }"
          ><Settings2 :size="20" />系统设置</RouterLink
        >
      </nav>
      <div class="sidebar-bottom">
        <div v-if="stats" class="storage-card">
          <div class="storage-heading">
            <HardDrive :size="17" /><strong>存储空间</strong
            ><span>{{ Math.round(usedPercent) }}%</span>
          </div>
          <div class="progress-track">
            <span :style="{ width: `${usedPercent}%` }"></span>
          </div>
          <p>
            {{ bytes(stats.usedBytes) }}
            <span>/ {{ bytes(stats.settings.quotaBytes) }}</span>
          </p>
        </div>
        <div class="sidebar-user">
          <span class="avatar">{{
            auth.username.slice(0, 1).toUpperCase()
          }}</span>
          <div>
            <strong>{{ auth.username }}</strong
            ><small>管理员</small>
          </div>
          <button
            class="icon-button"
            aria-label="退出登录"
            title="退出登录"
            @click="logout"
          >
            <LogOut :size="18" />
          </button>
        </div>
      </div>
    </aside>
    <div class="admin-body">
      <header class="admin-topbar">
        <span>咕咕盘 <span class="breadcrumb-slash">/</span> {{ heading }}</span
        ><RouterLink to="/" class="text-button"
          >取件首页<ArrowUpRight :size="15"
        /></RouterLink>
      </header>
      <main class="workspace">
        <div class="page-heading">
          <div>
            <h1>{{ heading }}</h1>
            <p>{{ subheading }}</p>
          </div>
          <button
            v-if="tab === 'files'"
            class="button primary"
            :disabled="!stats"
            @click="uploadBox?.open()"
          >
            <Upload :size="17" />上传文件</button
          ><button
            v-if="tab === 'inboxes'"
            class="button primary"
            :disabled="!stats"
            @click="openCreate('inbox')"
          >
            <Plus :size="18" />新建收件箱
          </button>
        </div>
        <div v-if="loadError" class="error-banner" role="alert">
          <span>{{ loadError }}</span
          ><button class="text-button" @click="load">
            <RefreshCw :size="16" />重试
          </button>
        </div>
        <div v-if="loading" class="loading-state">
          <Loader2 :size="26" class="spin" />正在打开文件空间…
        </div>
        <template v-else-if="stats">
          <template v-if="tab === 'files'">
            <UploadBox
              ref="uploadBox"
              endpoint="/admin/files"
              :max-bytes="stats.settings.maxFileBytes"
              :days="stats.settings.retentionDays"
              @complete="load"
            />
            <div class="stats-grid">
              <div class="stat-card">
                <span class="stat-icon green"><HardDrive :size="20" /></span>
                <div>
                  <span>已用空间</span
                  ><strong
                    >{{ bytes(stats.usedBytes)
                    }}<small>
                      / {{ bytes(stats.settings.quotaBytes) }}</small
                    ></strong
                  >
                </div>
              </div>
              <div class="stat-card">
                <span class="stat-icon blue"><Files :size="20" /></span>
                <div>
                  <span>文件总数</span
                  ><strong>{{ stats.fileCount }}<small> 个文件</small></strong>
                </div>
              </div>
              <div class="stat-card">
                <span class="stat-icon orange"><Link2 :size="20" /></span>
                <div>
                  <span>有效分享</span
                  ><strong>{{ activeShares }}<small> 个链接</small></strong>
                </div>
              </div>
            </div>
            <section class="file-section">
              <div class="file-toolbar">
                <div class="filter-tabs">
                  <button
                    :class="{ active: source === 'all' }"
                    @click="source = 'all'"
                  >
                    全部文件</button
                  ><button
                    :class="{ active: source === 'sent' }"
                    @click="source = 'sent'"
                  >
                    我的上传</button
                  ><button
                    :class="{ active: source === 'received' }"
                    @click="source = 'received'"
                  >
                    收到的文件
                  </button>
                </div>
                <div class="search-box">
                  <Search :size="17" /><input
                    v-model="query"
                    placeholder="搜索文件名"
                    aria-label="搜索文件名"
                  />
                </div>
              </div>
              <div v-if="selected.length" class="selection-bar">
                <a
                  class="text-button"
                  :href="`/api/admin/files/archive?${archiveQuery}`"
                  ><Download :size="16" />打包下载</a
                >
                <span>已选择 {{ selected.length }} 个文件</span
                ><button class="text-button" @click="openCreate('share')">
                  <Link2 :size="16" />创建分享</button
                ><button
                  class="icon-button"
                  aria-label="取消选择"
                  @click="selected = []"
                >
                  <X :size="16" />
                </button>
              </div>
              <div class="table-wrap">
                <table class="file-table">
                  <thead>
                    <tr>
                      <th class="checkbox-cell">
                        <input
                          type="checkbox"
                          :checked="allSelected"
                          aria-label="选择本页所有有效文件"
                          @change="toggleAll"
                        />
                      </th>
                      <th>文件名</th>
                      <th>大小</th>
                      <th>来源</th>
                      <th>保留时间</th>
                      <th class="actions-heading">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="file in pageFiles" :key="file.id">
                      <td class="checkbox-cell">
                        <input
                          v-model="selected"
                          type="checkbox"
                          :value="file.id"
                          :disabled="file.expiresAt <= Date.now()"
                          :aria-label="`选择 ${file.name}`"
                        />
                      </td>
                      <td>
                        <div class="file-name-cell">
                          <FileGlyph :name="file.name" />
                          <div>
                            <strong :title="file.name">{{ file.name }}</strong
                            ><small>{{ date(file.createdAt) }}</small>
                          </div>
                        </div>
                      </td>
                      <td class="nowrap muted">{{ bytes(file.size) }}</td>
                      <td>
                        <span
                          class="source-pill"
                          :class="{ received: file.inboxToken }"
                          :title="
                            file.inboxToken
                              ? inboxNames.get(file.inboxToken)
                              : ''
                          "
                          ><Inbox v-if="file.inboxToken" :size="12" />{{
                            file.inboxToken
                              ? inboxNames.get(file.inboxToken) || "收件箱"
                              : "我的上传"
                          }}</span
                        >
                      </td>
                      <td class="nowrap">
                        <span
                          class="expiry-label"
                          :class="{
                            soon: file.expiresAt - Date.now() < 86400000,
                          }"
                          ><Clock3 :size="13" />{{
                            expiry(file.expiresAt)
                          }}</span
                        >
                      </td>
                      <td>
                        <div class="row-actions">
                          <template v-if="file.expiresAt > Date.now()"
                            ><a
                              class="icon-button"
                              :href="`/api/admin/files/${file.id}/download`"
                              aria-label="下载文件"
                              title="下载"
                              ><Download :size="17" /></a
                            ><button
                              class="icon-button"
                              aria-label="分享文件"
                              title="分享"
                              @click="openCreate('share', file)"
                            >
                              <Link2 :size="17" /></button
                            ><button
                              class="icon-button"
                              aria-label="延长保留时间"
                              title="延长保留时间"
                              @click="extend(file)"
                            >
                              <CalendarPlus :size="17" /></button></template
                          ><button
                            class="icon-button danger-hover"
                            aria-label="删除文件"
                            title="删除"
                            @click="removeFile(file)"
                          >
                            <Trash2 :size="16" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div v-if="!pageFiles.length" class="empty-state">
                <FolderOpen :size="36" :stroke-width="1.2" />
                <h3>
                  {{
                    query || source !== "all"
                      ? "没有找到匹配的文件"
                      : "这里准备好接收文件了"
                  }}
                </h3>
                <p>
                  {{
                    query || source !== "all"
                      ? "试试其他关键词或文件分类"
                      : "拖入文件，或点击上方的上传按钮"
                  }}
                </p>
              </div>
              <div class="table-footer">
                <span>共 {{ filteredFiles.length }} 个文件</span>
                <div v-if="pageCount > 1" class="pagination">
                  <button :disabled="page === 1" @click="page--">上一页</button
                  ><span>{{ page }} / {{ pageCount }}</span
                  ><button :disabled="page === pageCount" @click="page++">
                    下一页
                  </button>
                </div>
                <span v-else class="table-footer-note"
                  ><ShieldCheck :size="13" />文件仅通过授权链接访问</span
                >
              </div>
            </section>
          </template>
          <TextPanel
            v-else-if="tab === 'texts'"
            :links="allLinks.filter((link) => link.kind === 'TEXT')"
            @created="textCreated"
            @changed="load"
          />
          <template v-else-if="tab === 'shares' || tab === 'inboxes'">
            <div class="links-toolbar">
              <span
                >{{ visibleLinks.length }} 个{{
                  tab === "shares" ? "分享" : "收件箱"
                }}</span
              >
              <div class="search-box">
                <Search :size="17" /><input
                  v-model="query"
                  placeholder="搜索名称"
                  aria-label="搜索名称"
                />
              </div>
            </div>
            <div v-if="visibleLinks.length" class="links-grid">
              <article
                v-for="link in visibleLinks"
                :key="link.token"
                class="link-card"
              >
                <div class="link-card-top">
                  <span
                    class="link-card-icon"
                    :class="{ orange: link.kind === 'INBOX' }"
                    ><Inbox v-if="link.kind === 'INBOX'" :size="23" /><Link2
                      v-else
                      :size="23" /></span
                  ><span
                    class="state-pill"
                    :class="{ inactive: !active(link) }"
                    >{{
                      link.revoked
                        ? "已关闭"
                        : active(link)
                          ? "使用中"
                          : "已过期"
                    }}</span
                  >
                </div>
                <h2>{{ link.title }}</h2>
                <p class="link-note">
                  {{
                    link.note ||
                    (link.kind === "SHARE"
                      ? "通过链接下载分享的文件"
                      : "通过链接向此收件箱上传文件")
                  }}
                </p>
                <div class="link-meta">
                  <span
                    ><Clock3 :size="14" />{{ date(link.expiresAt) }} 到期</span
                  ><span v-if="link.passwordProtected"
                    ><LockKeyhole :size="14" />密码保护</span
                  ><span v-if="link.kind === 'INBOX'"
                    >最多 {{ link.maxFiles }} 个文件 ·
                    {{ bytes(link.quotaBytes) }}</span
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
                  <button class="text-button" @click="showResult(link)">
                    <QrCode :size="16" />查看链接</button
                  ><button
                    v-if="active(link)"
                    class="text-button"
                    @click="revoke(link)"
                  >
                    关闭链接
                  </button>
                </div>
              </article>
            </div>
            <div v-else class="empty-state empty-panel">
              <component
                :is="tab === 'shares' ? Link2 : Inbox"
                :size="38"
                :stroke-width="1.3"
              />
              <h3>
                {{
                  query
                    ? "没有找到匹配的链接"
                    : tab === "shares"
                      ? "还没有分享链接"
                      : "等待第一份来件"
                }}
              </h3>
              <p>
                {{
                  query
                    ? "试试其他关键词"
                    : tab === "shares"
                      ? "在「我的文件」中选择文件，即可创建分享"
                      : "创建收件箱后，把链接发送给需要上传文件的人"
                }}
              </p>
              <RouterLink
                v-if="!query && tab === 'shares'"
                to="/admin"
                class="button secondary"
                >前往我的文件<ArrowUpRight :size="16" /></RouterLink
              ><button
                v-if="!query && tab === 'inboxes'"
                class="button secondary"
                @click="openCreate('inbox')"
              >
                <Plus :size="16" />新建收件箱
              </button>
            </div>
          </template>
          <template v-else-if="tab === 'settings'">
            <div class="settings-grid">
              <section class="settings-card">
                <div class="settings-heading">
                  <HardDrive :size="21" />
                  <h2>存储设置</h2>
                </div>
                <form class="form-stack" @submit.prevent="saveSettings">
                  <label
                    >总存储额度（GB）<input
                      v-model.number="settingsQuota"
                      type="number"
                      min="0.001"
                      max="100000"
                      step="0.001"
                      required /></label
                  ><label
                    >单文件大小上限（GB）<input
                      v-model.number="settingsMaxFile"
                      type="number"
                      min="0.001"
                      :max="settingsQuota"
                      step="0.001"
                      required /></label
                  ><label
                    >默认文件保留天数<input
                      v-model.number="settingsRetention"
                      type="number"
                      min="1"
                      max="365"
                      required
                  /></label>
                  <p class="form-help">
                    保留时间应用于新上传文件。到期后停止下载，并由定时任务清理磁盘文件。
                  </p>
                  <button class="button primary" :disabled="saving">
                    <Check :size="17" />保存设置
                  </button>
                </form>
              </section>
              <section class="settings-card">
                <div class="settings-heading">
                  <LockKeyhole :size="21" />
                  <h2>修改密码</h2>
                </div>
                <form class="form-stack" @submit.prevent="changePassword">
                  <label
                    >当前密码<input
                      v-model="currentPassword"
                      type="password"
                      autocomplete="current-password"
                      maxlength="128"
                      required /></label
                  ><label
                    >新密码<input
                      v-model="newPassword"
                      type="password"
                      autocomplete="new-password"
                      minlength="10"
                      maxlength="64"
                      required
                      placeholder="至少 10 个字符" /></label
                  ><label
                    >确认新密码<input
                      v-model="repeatPassword"
                      type="password"
                      autocomplete="new-password"
                      minlength="10"
                      maxlength="64"
                      required
                  /></label>
                  <p class="form-help">
                    修改后所有管理会话都会退出，需要使用新密码重新登录。
                  </p>
                  <button class="button secondary" :disabled="saving">
                    更新密码
                  </button>
                </form>
              </section>
            </div>
          </template>
        </template>
        <footer class="workspace-footer">
          <span>GuGuPan · 小巧，自在，刚刚好。</span><span>文件中转站</span>
        </footer>
      </main>
    </div>
    <Modal
      v-if="modal === 'share' || modal === 'inbox'"
      :title="modal === 'share' ? '创建分享链接' : '新建收件箱'"
      @close="!saving && (modal = '')"
      ><form class="form-stack" @submit.prevent="createLink">
        <div v-if="modal === 'share'" class="form-info">
          <Files :size="17" />分享已选择的 {{ selected.length }} 个文件
        </div>
        <label
          >{{ modal === "share" ? "分享名称" : "收件箱名称"
          }}<input
            v-model="title"
            maxlength="120"
            placeholder="为这次传输起个名字"
            required /></label
        ><label
          >备注<span class="optional">选填</span
          ><textarea
            v-model="note"
            rows="2"
            maxlength="500"
            placeholder="给对方留句话"
          />
        </label>
        <div class="form-row">
          <label
            >链接有效期<select v-model.number="days">
              <option :value="1">1 天</option>
              <option :value="3">3 天</option>
              <option :value="7">7 天</option>
              <option :value="30">30 天</option>
            </select></label
          ><label
            >访问密码<span class="optional">选填</span
            ><input
              v-model="password"
              type="text"
              autocomplete="off"
              minlength="4"
              maxlength="64"
              placeholder="至少 4 个字符"
          /></label>
        </div>
        <template v-if="modal === 'inbox'"
          ><div class="form-row">
            <label
              >收件总容量（GB）<input
                v-model.number="inboxQuota"
                type="number"
                min="0.001"
                step="0.001"
                :max="stats ? stats.settings.quotaBytes / gib : 20"
                required /></label
            ><label
              >最多文件数<input
                v-model.number="inboxMaxFiles"
                type="number"
                min="1"
                max="1000"
                required
            /></label>
          </div>
          <label
            >单文件大小上限（GB）<input
              v-model.number="inboxMaxFile"
              type="number"
              min="0.001"
              step="0.001"
              :max="
                Math.min(
                  inboxQuota,
                  stats ? stats.settings.maxFileBytes / gib : 1,
                )
              "
              required
          /></label>
          <p class="form-help">
            持有链接的人可以上传文件。收到的文件仅管理员可查看和下载，共享系统存储额度。
          </p></template
        >
        <p v-else class="form-help">
          链接有效期和文件保留时间独立，文件到期后将停止下载。
        </p>
        <p v-if="formError" class="inline-error" role="alert">
          {{ formError }}
        </p>
        <button class="button primary wide" :disabled="saving">
          <Loader2 v-if="saving" :size="17" class="spin" /><Link2
            v-else
            :size="17"
          />{{ saving ? "正在创建…" : "生成链接" }}
        </button>
      </form></Modal
    >
    <Modal
      v-if="modal === 'result' && result"
      title="链接已准备好"
      @close="modal = ''"
      ><div class="result-info">
        <span class="success-mark"><Check :size="23" /></span>
        <h3>{{ result.title }}</h3>
        <p>
          {{
            result.kind !== "INBOX"
              ? "把链接发给对方，即可下载文件"
              : "把链接发给对方，即可向你上传文件"
          }}
        </p>
      </div>
      <div class="result-link">
        <input
          :value="linkUrl(result)"
          readonly
          aria-label="分享链接"
          @focus="($event.target as HTMLInputElement).select()"
        /><button
          class="icon-button"
          aria-label="复制链接"
          @click="copy(linkUrl(result!))"
        >
          <Copy :size="18" />
        </button>
      </div>
      <div class="result-details">
        <div>
          <span>取件码</span><strong>{{ result.code }}</strong>
        </div>
        <div v-if="result.passwordProtected">
          <span>访问密码</span><strong>{{ resultPassword || "已设置" }}</strong>
        </div>
        <div>
          <span>有效期至</span><strong>{{ date(result.expiresAt) }}</strong>
        </div>
      </div>
      <img v-if="qr" class="qr-image" :src="qr" alt="扫描二维码打开链接" />
      <p v-if="!active(result)" class="inline-error">此链接已过期或已关闭</p>
      <button class="button primary wide" @click="copyShare(result!)">
        <Copy :size="17" />复制分享信息
      </button></Modal
    >
  </div>
</template>
