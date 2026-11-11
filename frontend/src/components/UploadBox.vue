<script setup lang="ts">
import { ref, reactive, markRaw, onBeforeUnmount } from "vue";
import {
  UploadCloud,
  Plus,
  X,
  CheckCircle2,
  RotateCw,
  AlertCircle,
  Pause,
  Trash2,
} from "lucide-vue-next";
import { sha256 } from "@noble/hashes/sha2.js";
import { bytesToHex } from "@noble/hashes/utils.js";
import { api, auth, refreshSession, bytes, message } from "../api";
import FileGlyph from "./FileGlyph.vue";
const props = defineProps<{
  endpoint: string;
  maxBytes: number;
  days?: number;
  disabled?: boolean;
}>();
const emit = defineEmits<{ complete: [] }>();
interface Task {
  id: number;
  file: File;
  progress: number;
  state: "waiting" | "uploading" | "done" | "error" | "cancelled" | "paused";
  error: string;
  stage: string;
  uploadId?: string;
  storageKey?: string;
  xhr?: XMLHttpRequest;
}
interface UploadStatus {
  id: string;
  chunkSize: number;
  uploaded: number[];
  finished: boolean;
  sha256: string;
  size: number;
}
const input = ref<HTMLInputElement>();
const tasks = reactive<Task[]>([]);
const dragging = ref(false);
const endpoint = props.endpoint.replace(/\/files$/, "/uploads");
let running = false;
let disposed = false;
let sequence = 0;
function open() {
  input.value?.click();
}
defineExpose({ open });
function add(files: FileList | File[]) {
  if (props.disabled) return;
  for (const file of Array.from(files)) {
    tasks.push({
      id: ++sequence,
      file: markRaw(file),
      progress: 0,
      stage: "",
      state: file.size > props.maxBytes ? "error" : "waiting",
      error:
        file.size > props.maxBytes
          ? `超过单文件上限 ${bytes(props.maxBytes)}`
          : "",
    });
  }
  void process();
}
function selected(event: Event) {
  const target = event.target as HTMLInputElement;
  if (target.files) add(target.files);
  target.value = "";
}
function drop(event: DragEvent) {
  dragging.value = false;
  if (event.dataTransfer?.files) add(event.dataTransfer.files);
}
function pause(task: Task) {
  task.state = "paused";
  task.xhr?.abort();
}
async function cancel(task: Task) {
  task.state = "cancelled";
  task.xhr?.abort();
  try {
    if (task.uploadId) await api(`${endpoint}/${task.uploadId}`, "DELETE");
    if (task.storageKey) cached(task.storageKey, null);
  } catch (error) {
    task.error = message(error);
    task.state = "error";
  }
}
function retry(task: Task) {
  task.state = "waiting";
  task.error = "";
  void process();
}
function interrupted(task: Task) {
  return disposed || task.state !== "uploading";
}
function label(task: Task) {
  if (task.state === "done") return "上传完成";
  if (task.state === "cancelled") return "已取消";
  if (task.state === "paused") return "已暂停，可继续上传";
  if (task.state === "error") return task.error;
  if (task.state === "waiting") return "等待上传";
  return task.stage || `${task.progress}%`;
}
async function fingerprint(task: Task) {
  const hash = sha256.create();
  const step = 4 * 1024 * 1024;
  for (let offset = 0; offset < task.file.size; offset += step) {
    if (interrupted(task)) throw new Error("已暂停");
    hash.update(
      new Uint8Array(
        await task.file.slice(offset, offset + step).arrayBuffer(),
      ),
    );
    task.stage = `正在校验文件 ${Math.round((Math.min(offset + step, task.file.size) / task.file.size) * 100)}%`;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  return bytesToHex(hash.digest());
}
async function sendPart(
  task: Task,
  blob: Blob,
  index: number,
  completed: number,
) {
  const hash = bytesToHex(sha256(new Uint8Array(await blob.arrayBuffer())));
  await refreshSession();
  if (interrupted(task)) throw new Error("已暂停");
  await new Promise<void>((resolve, reject) => {
    const xhr = markRaw(new XMLHttpRequest());
    task.xhr = xhr;
    xhr.open("PUT", `/api${endpoint}/${task.uploadId}/parts/${index}`);
    xhr.setRequestHeader("Content-Type", "application/octet-stream");
    xhr.setRequestHeader("X-CSRF-TOKEN", auth.csrfToken);
    xhr.setRequestHeader("X-Chunk-SHA256", hash);
    xhr.upload.onprogress = (event) => {
      task.progress = Math.round(
        ((completed + event.loaded) / Math.max(1, task.file.size)) * 100,
      );
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve();
      else {
        let error = "分片上传失败，点击继续可重试";
        try {
          error = JSON.parse(xhr.responseText).message || error;
        } catch {
          /* Proxy response. */
        }
        reject(new Error(error));
      }
    };
    xhr.onerror = () => reject(new Error("网络中断，点击继续上传"));
    xhr.onabort = () => reject(new Error("已暂停"));
    xhr.send(blob);
  });
}
function cached(key: string, value?: string | null): string | null {
  try {
    if (value === null) localStorage.removeItem(key);
    else if (value !== undefined) localStorage.setItem(key, value);
    else return localStorage.getItem(key);
  } catch {
    /* Upload still works when browser storage is disabled. */
  }
  return null;
}
function newId() {
  const hex = bytesToHex(crypto.getRandomValues(new Uint8Array(16)));
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
async function process() {
  if (running || disposed) return;
  running = true;
  try {
    let task: Task | undefined;
    while (
      !disposed &&
      (task = tasks.find((item) => item.state === "waiting"))
    ) {
      const current = task;
      current.state = "uploading";
      try {
        const hash = await fingerprint(current);
        if (interrupted(current)) continue;
        current.storageKey = `gugupan-upload:${endpoint}:${hash}:${current.file.name}`;
        let status: UploadStatus | undefined;
        let savedId = cached(current.storageKey);
        if (savedId) {
          try {
            status = await api<UploadStatus>(`${endpoint}/${savedId}`);
          } catch (error) {
            // Only a missing or expired upload permits a new reservation.
            const probe = await fetch(`/api${endpoint}/${savedId}`, {
              cache: "no-store",
            });
            if (![404, 410].includes(probe.status)) throw error;
            if (probe.status === 410) {
              cached(current.storageKey, null);
              savedId = null;
            }
          }
        }
        if (!status) {
          savedId = savedId || newId();
          cached(current.storageKey, savedId);
          status = await api<UploadStatus>(endpoint, "POST", {
            id: savedId,
            name: current.file.name,
            size: current.file.size,
            sha256: hash,
            days: props.days,
          });
          cached(current.storageKey, status.id);
        }
        current.uploadId = status.id;
        if ((current.state as string) === "cancelled") {
          await cancel(current);
          continue;
        }
        if (interrupted(current)) continue;
        if (status.sha256 !== hash || status.size !== current.file.size)
          throw new Error("原文件不匹配，请重新选择");
        const uploaded = new Set(status.uploaded);
        let completed = status.uploaded.reduce(
          (sum, index) =>
            sum +
            Math.min(
              status!.chunkSize,
              current.file.size - index * status!.chunkSize,
            ),
          0,
        );
        current.progress = Math.round(
          (completed / Math.max(1, current.file.size)) * 100,
        );
        current.stage = "";
        if (!status.finished) {
          for (
            let index = 0;
            index * status.chunkSize < current.file.size;
            index++
          ) {
            if (interrupted(current)) break;
            if (uploaded.has(index)) continue;
            const blob = current.file.slice(
              index * status.chunkSize,
              (index + 1) * status.chunkSize,
            );
            let attempts = 0;
            while (true) {
              try {
                await sendPart(current, blob, index, completed);
                break;
              } catch (error) {
                if (interrupted(current) || ++attempts >= 3) throw error;
                await new Promise((resolve) => setTimeout(resolve, 1000));
              }
            }
            completed += blob.size;
          }
        }
        if (interrupted(current)) continue;
        current.stage = "正在保存并校验完整文件…";
        await api(`${endpoint}/${status.id}/complete`, "POST");
        cached(current.storageKey, null);
        current.state = "done";
        current.progress = 100;
        emit("complete");
      } catch (error) {
        if (!interrupted(current)) {
          current.state = "error";
          current.error = message(error);
        }
      } finally {
        current.xhr = undefined;
      }
    }
  } finally {
    running = false;
  }
}
onBeforeUnmount(() => {
  disposed = true;
  tasks.forEach((task) => task.xhr?.abort());
});
</script>
<template>
  <div
    class="upload-zone"
    :class="{ dragging, disabled }"
    @dragover.prevent="dragging = true"
    @dragleave.prevent="dragging = false"
    @drop.prevent="drop"
  >
    <input
      ref="input"
      type="file"
      multiple
      class="visually-hidden"
      :disabled="disabled"
      aria-label="选择上传文件"
      @change="selected"
    />
    <span class="upload-symbol"
      ><UploadCloud :size="32" :stroke-width="1.5"
    /></span>
    <div class="upload-copy">
      <h3>把文件拖到这里</h3>
      <p>
        单文件最大 {{ bytes(maxBytes)
        }}<span v-if="days"> · 上传后保留 {{ days }} 天</span>
      </p>
    </div>
    <button
      class="button secondary"
      type="button"
      :disabled="disabled"
      @click="open"
    >
      <Plus :size="17" />选择文件
    </button>
  </div>
  <p class="form-help">
    支持断点续传。重新打开页面后选择原文件，可恢复 24 小时内的上传进度。
  </p>
  <div v-if="tasks.length" class="upload-queue" aria-live="polite">
    <div class="queue-heading">
      <strong>传输列表</strong
      ><button
        class="text-button"
        @click="
          tasks.splice(
            0,
            tasks.length,
            ...tasks.filter((task) =>
              ['waiting', 'uploading', 'paused', 'error'].includes(task.state),
            ),
          )
        "
      >
        清除已结束
      </button>
    </div>
    <div v-for="task in tasks" :key="task.id" class="upload-task">
      <FileGlyph :name="task.file.name" />
      <div class="task-details">
        <strong :title="task.file.name">{{ task.file.name }}</strong
        ><small>{{ bytes(task.file.size) }} · {{ label(task) }}</small>
        <div v-if="task.state === 'uploading'" class="progress-track">
          <span :style="{ width: `${task.progress}%` }"></span>
        </div>
      </div>
      <CheckCircle2
        v-if="task.state === 'done'"
        :size="20"
        class="text-green"
      />
      <template v-else-if="task.state === 'error' || task.state === 'paused'"
        ><AlertCircle :size="18" class="text-danger" /><button
          class="icon-button"
          aria-label="继续上传"
          @click="retry(task)"
        >
          <RotateCw :size="17" /></button
      ></template>
      <button
        v-else-if="task.state !== 'cancelled'"
        class="icon-button"
        aria-label="暂停上传"
        @click="pause(task)"
      >
        <Pause :size="18" />
      </button>
      <button
        v-if="task.state !== 'done' && task.state !== 'cancelled'"
        class="icon-button danger-hover"
        aria-label="取消并清理上传"
        @click="cancel(task)"
      >
        <Trash2 :size="17" />
      </button>
    </div>
  </div>
</template>
