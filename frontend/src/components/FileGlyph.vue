<script setup lang="ts">
import { computed } from "vue";
import {
  FileText,
  FileArchive,
  FileImage,
  FileAudio,
  FileVideo,
  File,
  FileCode2,
} from "lucide-vue-next";
const props = defineProps<{ name: string }>();
const category = computed(() => {
  const ext = props.name.split(".").pop()?.toLowerCase() || "";
  if (["png", "jpg", "jpeg", "gif", "webp", "svg"].includes(ext))
    return { icon: FileImage, color: "purple" };
  if (["zip", "rar", "7z", "tar", "gz"].includes(ext))
    return { icon: FileArchive, color: "orange" };
  if (["mp3", "wav", "flac", "m4a"].includes(ext))
    return { icon: FileAudio, color: "purple" };
  if (["mp4", "mov", "mkv", "avi"].includes(ext))
    return { icon: FileVideo, color: "blue" };
  if (["java", "js", "ts", "json", "py", "xml", "yml"].includes(ext))
    return { icon: FileCode2, color: "green" };
  if (["pdf", "doc", "docx", "txt", "md", "xlsx", "csv", "pptx"].includes(ext))
    return { icon: FileText, color: "blue" };
  return { icon: File, color: "gray" };
});
</script>
<template>
  <span class="file-glyph" :class="category.color"
    ><component :is="category.icon" :size="21" :stroke-width="1.7"
  /></span>
</template>
