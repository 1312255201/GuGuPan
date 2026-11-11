<script setup lang="ts">
import { ref, reactive, markRaw, onBeforeUnmount } from "vue";
import {
  UploadCloud,
  Plus,
  X,
  CheckCircle2,
  RotateCw,
  AlertCircle,
} from "lucide-vue-next";
import { auth, refreshSession, bytes, message } from "../api";
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
  state: "waiting" | "uploading" | "done" | "error" | "cancelled";
  error: string;
  xhr?: XMLHttpRequest;
}
const input = ref<HTMLInputElement>();
const tasks = reactive<Task[]>([]);
const dragging = ref(false);
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
function cancel(task: Task) {
  task.state = "cancelled";
  task.xhr?.abort();
}
function retry(task: Task) {
  task.state = "waiting";
  task.error = "";
  task.progress = 0;
  void process();
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
        await refreshSession();
        if (disposed || (current.state as string) === "cancelled") continue;
        await new Promise<void>((resolve, reject) => {
          const xhr = markRaw(new XMLHttpRequest());
          current.xhr = xhr;
          const query = new URLSearchParams({ name: current.file.name });
          if (props.days) query.set("days", String(props.days));
          xhr.open("POST", `/api${props.endpoint}?${query}`);
          xhr.setRequestHeader("Content-Type", "application/octet-stream");
          xhr.setRequestHeader("X-CSRF-TOKEN", auth.csrfToken);
          xhr.upload.onprogress = (event) => {
            if (event.lengthComputable)
              current.progress = Math.round((event.loaded / event.total) * 100);
          };
          xhr.onload = () => {
            if (xhr.status >= 200 && xhr.status < 300) resolve();
            else {
              let error = "上传失败，请稍后重试";
              try {
                error = JSON.parse(xhr.responseText).message || error;
              } catch {
                /* Non-JSON proxy error. */
              }
              reject(new Error(error));
            }
          };
          xhr.onerror = () => reject(new Error("网络中断，请重新上传"));
          xhr.onabort = () => reject(new Error("已取消"));
          xhr.send(current.file);
        });
        current.state = "done";
        current.progress = 100;
        emit("complete");
      } catch (error) {
        if ((current.state as string) !== "cancelled") {
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
              ['waiting', 'uploading'].includes(task.state),
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
        ><small
          >{{ bytes(task.file.size) }} ·
          {{
            task.state === "done"
              ? "上传完成"
              : task.state === "cancelled"
                ? "已取消"
                : task.state === "error"
                  ? task.error
                  : task.state === "waiting"
                    ? "等待上传"
                    : task.progress === 100
                      ? "正在保存…"
                      : `${task.progress}%`
          }}</small
        >
        <div v-if="task.state === 'uploading'" class="progress-track">
          <span :style="{ width: `${task.progress}%` }"></span>
        </div>
      </div>
      <CheckCircle2
        v-if="task.state === 'done'"
        :size="20"
        class="text-green"
      />
      <template v-else-if="task.state === 'error'"
        ><AlertCircle :size="18" class="text-danger" /><button
          class="icon-button"
          aria-label="重试上传"
          @click="retry(task)"
        >
          <RotateCw :size="17" /></button
      ></template>
      <button
        v-else-if="task.state !== 'cancelled'"
        class="icon-button"
        aria-label="取消上传"
        @click="cancel(task)"
      >
        <X :size="18" />
      </button>
    </div>
  </div>
</template>
