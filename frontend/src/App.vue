<script setup lang="ts">
import { CheckCircle2, AlertCircle } from "lucide-vue-next";
import { notification } from "./api";
</script>
<template>
  <RouterView />
  <Transition name="toast">
    <div
      v-if="notification.message"
      class="toast"
      :class="notification.kind"
      role="status"
    >
      <AlertCircle
        v-if="notification.kind === 'error'"
        :size="19"
      /><CheckCircle2 v-else :size="19" />
      {{ notification.message }}
    </div>
  </Transition>
</template>
