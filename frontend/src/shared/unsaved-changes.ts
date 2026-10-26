import { onBeforeUnmount, onMounted, type Ref } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';

/** Drafts stay in memory. Browser refresh uses its native unsaved-work prompt. */
export function useUnsavedChanges(dirty: Ref<boolean>, message: () => string): void {
  onBeforeRouteLeave(() => !dirty.value || window.confirm(message()));
  const beforeUnload = (event: BeforeUnloadEvent) => {
    if (!dirty.value) return;
    event.preventDefault();
    event.returnValue = '';
  };
  onMounted(() => window.addEventListener('beforeunload', beforeUnload));
  onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload));
}
