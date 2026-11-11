<script setup lang="ts">
import { Bird } from "lucide-vue-next";
</script>
<template>
  <RouterLink to="/" class="brand" aria-label="咕咕盘首页">
    <span class="brand-mark"><Bird :size="27" :stroke-width="1.65" /></span>
    <span
      ><strong>咕咕盘<span class="brand-dot">.</span></strong
      ><small>GuGuPan</small></span
    >
  </RouterLink>
</template>
