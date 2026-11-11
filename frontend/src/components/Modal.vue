<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from "vue";
import { X } from "lucide-vue-next";
defineProps<{ title: string }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const previousFocus = document.activeElement as HTMLElement | null;
onMounted(() => dialog.value?.showModal());
onBeforeUnmount(() => previousFocus?.focus());
</script>
<template>
  <dialog
    ref="dialog"
    class="modal"
    aria-labelledby="modal-title"
    @cancel.prevent="emit('close')"
    @click="
      (event) => {
        if (event.target === dialog) emit('close');
      }
    "
  >
    <div class="modal-heading">
      <h2 id="modal-title">{{ title }}</h2>
      <button
        class="icon-button"
        type="button"
        aria-label="关闭"
        @click="emit('close')"
      >
        <X :size="20" />
      </button>
    </div>
    <slot />
  </dialog>
</template>
