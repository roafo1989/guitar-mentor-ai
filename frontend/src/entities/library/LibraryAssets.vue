<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import ScoreReader from "../../shared/score/ScoreReader.vue";
import type { LibraryFile } from "./library.service";
import { assetPath, type PublicationAsset } from "./community.service";
import { ApiFailure } from "../../shared/api-failure";
import { libraryError } from "./library-errors";
const props = defineProps<{
  kind: "material" | "publication";
  id: string;
  assets: (LibraryFile | PublicationAsset)[];
}>();
const { t } = useI18n();
const chosen = ref(""),
  pdfOpen = ref(false),
  mediaUrl = ref(""),
  error = ref(""),
  loading = ref(false);
const audio = ref<HTMLAudioElement | null>(null);
let controller: AbortController | null = null,
  generation = 0;
function stop() {
  ++generation;
  controller?.abort();
  controller = null;
  audio.value?.pause();
  if (audio.value) {
    audio.value.removeAttribute("src");
    audio.value.load();
  }
  if (mediaUrl.value) URL.revokeObjectURL(mediaUrl.value);
  mediaUrl.value = "";
  chosen.value = "";
  pdfOpen.value = false;
  error.value = "";
  loading.value = false;
}
watch(() => [props.kind, props.id, props.assets], stop, { flush: "sync" });
onBeforeUnmount(stop);
async function view(asset: LibraryFile | PublicationAsset, download = false) {
  stop();
  const ticket = generation;
  chosen.value = asset.id;
  const path = assetPath(props.kind, props.id, asset.id);
  if (asset.downloadUrl !== path) {
    error.value = t("community.errors.invalid");
    return;
  }
  if (!download && asset.format === "PDF") {
    pdfOpen.value = true;
    return;
  }
  if (!download && asset.role === "SCORE_ORIGINAL") return;
  const preview = !download && asset.role === "PHOTO_ORIGINAL";
  const expected = assetPath(props.kind, props.id, asset.id, preview);
  if (preview && asset.previewUrl !== expected) {
    error.value = t("community.unavailable");
    return;
  }
  controller = new AbortController();
  loading.value = true;
  try {
    const response = await fetch(expected, {
      credentials: "same-origin",
      cache: "no-store",
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new ApiFailure("HTTP", response.status);
    }
    const mime = response.headers.get("content-type")?.split(";")[0];
    if (
      !download &&
      !(preview
        ? ["image/jpeg", "image/png"].includes(mime ?? "")
        : ["audio/mpeg", "audio/wav", "audio/x-wav"].includes(mime ?? ""))
    )
      throw Error("Invalid media");
    const limit = download
      ? asset.byteSize
      : preview
        ? 5 * 1024 * 1024
        : 20 * 1024 * 1024;
    const reader = response.body?.getReader();
    if (!reader) throw Error("Empty media");
    const parts: Uint8Array<ArrayBuffer>[] = [];
    let size = 0;
    try {
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.byteLength;
        if (size > limit) throw Error("Media size");
        parts.push(value);
      }
    } finally {
      await reader.cancel().catch(() => {});
    }
    if (ticket !== generation) return;
    if (download && size !== asset.byteSize)
      throw new ApiFailure("INVALID_RESPONSE");
    const url = URL.createObjectURL(new Blob(parts, { type: mime }));
    if (download) {
      const a = document.createElement("a");
      a.href = url;
      a.download = asset.originalName.replace(/[\\/\x00-\x1f\x7f]/g, "_");
      a.click();
      URL.revokeObjectURL(url);
    } else mediaUrl.value = url;
  } catch (e) {
    if (ticket === generation && !controller?.signal.aborted)
      error.value = libraryError(e, t);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
</script>
<template>
  <section :aria-label="t('library.files')" class="asset-list">
    <article v-for="asset in assets" :key="asset.id">
      <strong>{{ asset.originalName }}</strong> · {{ asset.format }} ·
      {{ (asset.byteSize / 1024 / 1024).toFixed(2) }} MB
      <p>{{ t("community.roles." + asset.role) }}</p>
      <p v-if="'rightsBasis' in asset">
        {{ t("community.bases." + asset.rightsBasis) }} ·
        {{ t("community.provenances." + asset.provenance) }}
      </p>
      <p v-if="'attribution' in asset && asset.attribution">
        {{ t("community.attribution") }}: {{ asset.attribution }}
      </p>
      <p v-if="'processingState' in asset && asset.processingState !== 'READY'">
        {{ t("community.unavailable") }}
      </p>
      <template v-else>
        <button type="button" @click="view(asset)">
          {{ t("community.view") }}
        </button>
        <button type="button" @click="view(asset, true)">
          {{ t("library.download") }}
        </button>
      </template>
      <p v-if="asset.role === 'SCORE_ORIGINAL' && (kind === 'publication' || !['GP3', 'GP4', 'GP5'].includes(asset.format))">
        {{ t("community.noConversion") }}
      </p>
      <ScoreReader
        v-if="chosen === asset.id && asset.format === 'PDF'"
        :key="id + asset.id"
        v-model:open="pdfOpen"
        :source="
          kind === 'material'
            ? { kind, materialId: id, assetId: asset.id }
            : { kind, publicationId: id, assetId: asset.id }
        "
        :pdf-url="assetPath(kind, id, asset.id)"
        :score-url="null"
        :title="asset.originalName"
      />
      <img
        v-if="
          chosen === asset.id && mediaUrl && asset.role === 'PHOTO_ORIGINAL'
        "
        :src="mediaUrl"
        :alt="asset.originalName"
      />
      <audio
        v-if="
          chosen === asset.id &&
          mediaUrl &&
          (asset.role === 'ORIGINAL_RECORDING' ||
            asset.role === 'SYNTHESIZED_PREVIEW')
        "
        :ref="(el) => (audio = el as HTMLAudioElement | null)"
        :src="mediaUrl"
        controls
        preload="none"
        :aria-label="t('community.roles.' + asset.role)"
      />
    </article>
    <p v-if="loading" role="status">{{ t("library.loading") }}</p>
    <p v-if="error" role="alert">{{ error }}</p>
  </section>
</template>
<style scoped>
article {
  border-top: 1px solid #dce6e3;
  padding: 16px 0;
  overflow-wrap: anywhere;
}
p {
  margin: 8px 0;
}
button {
  min-height: 44px;
  margin: 4px;
  padding: 8px 14px;
  border: 1px solid #adc8be;
  border-radius: 8px;
  background: white;
  color: #285a54;
  cursor: pointer;
}
button:focus-visible {
  outline: 3px solid #28776d;
}
img {
  display: block;
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
}
audio {
  display: block;
  max-width: 100%;
  width: 100%;
}
</style>
