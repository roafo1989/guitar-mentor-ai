<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { auth } from "../../core/auth/auth";
import { libraryService, type LibraryMaterial } from "./library.service";
import {
  communityService,
  normalizeTags,
  provenance,
  type AssetRight,
  type AssetRole,
  type Capabilities,
  type Publication,
  type RightsBasis,
} from "./community.service";
import { libraryError } from "./library-errors";
import LibraryAssets from "./LibraryAssets.vue";
const props = defineProps<{ material: LibraryMaterial }>(),
  emit = defineEmits<{ updated: [material: LibraryMaterial] }>();
const { t } = useI18n();
const publications = ref<Publication[]>([]),
  capabilities = ref<Capabilities | null>(null),
  loading = ref(false),
  historyReady = ref(false),
  busy = ref(false),
  error = ref(""),
  notice = ref(""),
  capabilityError = ref("");
const root = ref<HTMLElement | null>(null);
const rightsOpen = ref(false),
  rights = ref<(AssetRight & { chosen: boolean })[]>([]),
  tags = ref(""),
  confirm = ref(false),
  replace = ref(false),
  withdrawn = ref(false);
const role = ref<AssetRole>("PHOTO_ORIGINAL"),
  files = ref<File[]>([]),
  input = ref<HTMLInputElement | null>(null),
  dialog = ref<HTMLDialogElement | null>(null);
const activePublication = computed(() =>
  publications.value.find((p) => p.state === "PUBLISHED"),
);
const hidden = computed(() => publications.value[0]?.state === "HIDDEN");
const changed = computed(
  () =>
    activePublication.value &&
    activePublication.value.sourceRevision !== props.material.revision,
);
const bases = Object.keys(provenance) as RightsBasis[];
let controller = new AbortController(),
  generation = 0,
  active = true,
  trigger: HTMLElement | null = null;
let rightsRevision = 0;
let publishKey = "",
  fingerprint = "",
  uploadKey = "",
  uploadFingerprint = "";
const accept = computed(
  () =>
    capabilities.value?.allowedExtensions[role.value]
      ?.map((x) => "." + x)
      .join(",") ?? "",
);
function reset() {
  ++generation;
  controller.abort();
  controller = new AbortController();
  publications.value = [];
  capabilities.value = null;
  rights.value = [];
  rightsOpen.value = false;
  dialog.value?.close();
  files.value = [];
  if (input.value) input.value.value = "";
  rightsRevision = 0;
  publishKey = "";
  fingerprint = "";
  uploadKey = "";
  uploadFingerprint = "";
  error.value = "";
  notice.value = "";
  capabilityError.value = "";
  historyReady.value = false;
  busy.value = false;
  withdrawn.value = false;
}
async function load() {
  const ticket = generation;
  loading.value = true;
  error.value = "";
  historyReady.value = false;
  await Promise.allSettled([
    (async () => {
      try {
        const history = await communityService.history(
          props.material.id,
          controller.signal,
        );
        if (active && ticket === generation) {
          publications.value = history;
          historyReady.value = true;
        }
      } catch (e) {
        if (active && ticket === generation && !controller.signal.aborted)
          error.value = libraryError(e, t);
      }
    })(),
    (async () => {
      try {
        const c = await communityService.capabilities(controller.signal);
        if (active && ticket === generation) {
          capabilities.value = c;
          capabilityError.value = "";
        }
      } catch (e) {
        if (active && ticket === generation && !controller.signal.aborted)
          capabilityError.value = libraryError(e, t);
      }
    })(),
  ]);
  if (ticket === generation) loading.value = false;
}
watch(
  () => props.material.id + "@" + (auth.actor?.accountId ?? ""),
  () => {
    reset();
    if (auth.actor) void load();
  },
  { immediate: true, flush: "sync" },
);
onBeforeUnmount(() => {
  active = false;
  reset();
});
function openRights(update = false) {
  trigger = document.activeElement as HTMLElement;
  const sameDraft =
    rightsRevision === props.material.revision &&
    rights.value.length === props.material.files.length &&
    rights.value.every((r) =>
      props.material.files.some((f) => f.id === r.assetId),
    );
  replace.value = update;
  if (!sameDraft) {
    withdrawn.value = false;
    confirm.value = false;
    tags.value = activePublication.value?.tags.join(", ") ?? "";
    rights.value = props.material.files.map((f) => ({
      assetId: f.id,
      chosen: false,
      provenance: "",
      rightsBasis: "" as RightsBasis,
      rightsEvidence: "",
      attribution: "",
    }));
    rightsRevision = props.material.revision;
  }
  rightsOpen.value = true;
  error.value = "";
  void nextTick(() => dialog.value?.showModal());
}
function restoreFocus() {
  if (trigger?.isConnected) trigger.focus();
  else
    root.value
      ?.querySelector<HTMLButtonElement>("button:not(:disabled)")
      ?.focus();
}
function close() {
  if (busy.value) return;
  dialog.value?.close();
  rightsOpen.value = false;
  restoreFocus();
}
function basis(r: AssetRight) {
  r.provenance = provenance[r.rightsBasis] ?? "";
}
async function mutate(action: () => Promise<void>) {
  if (busy.value) return;
  const ticket = generation;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await action();
  } catch (e) {
    if (active && ticket === generation && !controller.signal.aborted)
      error.value = libraryError(e, t);
  } finally {
    if (active && ticket === generation) busy.value = false;
  }
}
async function publish() {
  await mutate(async () => {
    const ticket = generation,
      material = props.material;
    const selected = rights.value
      .filter((r) => r.chosen)
      .map(({ chosen: _, ...r }) => r);
    if (
      !confirm.value ||
      !selected.length ||
      !selected.some(r=>material.files.some(f=>f.id===r.assetId && ['SCORE_ORIGINAL','PHOTO_ORIGINAL'].includes(f.role))) ||
      selected.some(r=>!material.files.some(f=>f.id===r.assetId && f.processingState==='READY')) ||
      selected.some(
        (r) =>
          !r.rightsBasis ||
          r.provenance !== provenance[r.rightsBasis] ||
          r.rightsEvidence.trim().length < 8,
      )
    ) {
      error.value = t("community.rightsRequired");
      return;
    }
    const normalized = normalizeTags(tags.value);
    const signature = JSON.stringify({
      id: material.id,
      revision: material.revision,
      selected,
      normalized,
    });
    if (signature !== fingerprint) {
      publishKey = crypto.randomUUID();
      fingerprint = signature;
    }
    if (replace.value && activePublication.value) {
      const old = activePublication.value;
      const result = await communityService.withdraw(
        material.id,
        old,
        controller.signal,
      );
      if (ticket !== generation) return;
      publications.value = publications.value.map((p) =>
        p.id === old.id ? result : p,
      );
      withdrawn.value = true;
    }
    const result = await communityService.publish(
      material,
      selected,
      normalized,
      publishKey,
      controller.signal,
    );
    if (ticket !== generation) return;
    publications.value = [
      result,
      ...publications.value.filter((p) => p.id !== result.id),
    ];
    if (result.state !== "PUBLISHED") {
      error.value = t("community.notActive");
      return;
    }
    notice.value = t("community.published");
    rightsOpen.value = false;
    dialog.value?.close();
    restoreFocus();
    publishKey = "";
    fingerprint = "";
    withdrawn.value = false;
    rightsRevision = 0;
    rights.value = [];
  });
}
async function withdraw() {
  await mutate(async () => {
    const p = activePublication.value,
      ticket = generation;
    if (!p) return;
    const result = await communityService.withdraw(
      props.material.id,
      p,
      controller.signal,
    );
    if (ticket !== generation) return;
    publications.value = publications.value.map((old) =>
      old.id === p.id ? result : old,
    );
    notice.value = t("community.withdrawn");
  });
}
async function upload() {
  await mutate(async () => {
    const c = capabilities.value,
      m = props.material,
      ticket = generation;
    if (!c?.enabled || !c.roles.includes(role.value) || !files.value.length) {
      error.value = t("community.limits");
      return;
    }
    const audio = role.value !== "PHOTO_ORIGINAL",
      count = m.files.filter((f) =>
        audio
          ? ["ORIGINAL_RECORDING", "SYNTHESIZED_PREVIEW"].includes(f.role)
          : ["SCORE_ORIGINAL", "PHOTO_ORIGINAL"].includes(f.role),
      ).length;
    if (
      files.value.length > c.maxFilesPerRequest ||
      m.files.length + files.value.length > c.maxFilesPerMaterial ||
      count + files.value.length >
        (audio ? c.maxAudio : c.maxPhotosAndScores) ||
      files.value.reduce((n, f) => n + f.size, 0) > c.maxRequestBytes ||
      files.value.some(
        (f) =>
          f.size <= 0 ||
          f.size > (audio ? c.maxAudioBytes : c.maxPhotoBytes) ||
          !c.allowedExtensions[role.value]?.includes(
            f.name.split(".").pop()?.toLowerCase() ?? "",
          ),
      )
    ) {
      error.value = t("community.limits");
      return;
    }
    const signature = JSON.stringify([
      m.id,
      m.revision,
      role.value,
      files.value.map((f) => [f.name, f.size, f.lastModified]),
    ]);
    if (signature !== uploadFingerprint) {
      uploadKey = crypto.randomUUID();
      uploadFingerprint = signature;
    }
    const updated = await communityService.addFiles(
      m,
      role.value,
      files.value,
      uploadKey,
      controller.signal,
    );
    if (ticket !== generation) return;
    files.value = [];
    if (input.value) input.value.value = "";
    uploadKey = "";
    uploadFingerprint = "";
    notice.value = t("community.mediaAdded");
    emit("updated", updated);
  });
}
async function remove(asset: string) {
  await mutate(async () => {
    const ticket = generation;
    await communityService.removeFile(props.material, asset, controller.signal);
    if (ticket !== generation) return;
    const updated = await libraryService.detail(
      props.material.id,
      controller.signal,
    );
    if (ticket !== generation) return;
    notice.value = t("community.mediaDeleted");
    emit("updated", updated);
  });
}
async function refresh() {
  if (loading.value || busy.value) return;
  loading.value = true;
  const ticket = generation;
  try {
    const updated = await libraryService.detail(
      props.material.id,
      controller.signal,
    );
    if (ticket !== generation) return;
    emit("updated", updated);
    await load();
  } catch (e) {
    if (ticket === generation) error.value = libraryError(e, t);
  } finally {
    if (ticket === generation) loading.value = false;
  }
}
</script>
<template>
  <section ref="root" class="sharing">
    <h2>{{ material.title }}</h2>
    <button @click="refresh" :disabled="busy || loading">
      {{ t("library.refresh") }}
    </button>
    <p v-if="loading" role="status">{{ t("library.loading") }}</p>
    <p v-if="historyReady">
      {{
        activePublication
          ? t("community.publishedRevision", {
              version: activePublication.version,
            })
          : t("community.private")
      }}
    </p>
    <p v-if="changed">{{ t("community.draftChanged") }}</p>
    <p v-if="hidden">{{ t("community.hidden") }}</p>
    <p v-if="error && !rightsOpen" role="alert">{{ error }}</p>
    <p v-if="withdrawn && !rightsOpen" role="status">
      {{ t("community.partial") }}
    </p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <template v-if="historyReady">
      <button
        v-if="!activePublication && !hidden"
        :disabled="busy"
        @click="openRights()"
      >
        {{ t("community.publish") }}
      </button>
      <template v-if="activePublication">
        <button :disabled="busy" @click="withdraw">
          {{ t("community.withdraw") }}
        </button>
        <button v-if="changed" :disabled="busy" @click="openRights(true)">
          {{ t("community.updatePublication") }}
        </button>
      </template>
      <details>
        <summary>{{ t("community.history") }}</summary>
        <p v-for="p in publications" :key="p.id">
          {{ t("community.publishedRevision", { version: p.version }) }} ·
          {{ t("community.states." + p.state) }}
        </p>
      </details>
    </template>
    <LibraryAssets kind="material" :id="material.id" :assets="material.files" />
    <details v-if="activePublication">
      <summary>{{ t("community.snapshot") }}</summary>
      <LibraryAssets
        :key="activePublication.id"
        kind="publication"
        :id="activePublication.id"
        :assets="activePublication.assets"
      />
    </details>
    <h3>{{ t("community.media") }}</h3>
    <p v-if="capabilityError" role="alert">{{ capabilityError }}</p>
    <p v-if="capabilities && !capabilities.enabled">
      {{ t("community.mediaOff") }}
    </p>
    <form v-if="capabilities?.enabled" @submit.prevent="upload">
      <label
        >{{ t("community.role")
        }}<select v-model="role" :disabled="busy">
          <option v-for="r in capabilities.roles" :key="r" :value="r">
            {{ t("community.roles." + r) }}
          </option>
        </select></label
      >
      <p>
        {{
          t("community.mediaLimits", {
            count: capabilities.maxFilesPerRequest,
            total: capabilities.maxFilesPerMaterial,
            photos: capabilities.maxPhotosAndScores,
            audio: capabilities.maxAudio,
            mb: capabilities.maxRequestBytes / 1024 / 1024,
            photoMb: capabilities.maxPhotoBytes / 1024 / 1024,
            audioMb: capabilities.maxAudioBytes / 1024 / 1024,
            minutes: capabilities.maxAudioDurationMillis / 60000,
          })
        }}
      </p>
      <p>
        {{
          t("community.mediaDetails", {
            pixels: capabilities.maxPhotoPixels / 1000000,
            side: capabilities.maxPhotoSide,
            min: capabilities.minAudioSampleRate,
            max: capabilities.maxAudioSampleRate,
            channels: capabilities.audioChannels.join("/"),
            wav: capabilities.wavEncoding ?? "—",
          })
        }}
      </p>
      <label
        >{{ t("library.files")
        }}<input
          ref="input"
          type="file"
          multiple
          :accept="accept"
          :disabled="busy"
          @change="
            files = Array.from(($event.target as HTMLInputElement).files ?? [])
          "
      /></label>
      <button :disabled="busy || !files.length">
        {{ t("community.addMedia") }}
      </button>
      <ul>
        <li v-for="f in material.files" :key="f.id">
          {{ f.originalName }}
          <button type="button" :disabled="busy" @click="remove(f.id)">
            {{ t("library.delete") }}
          </button>
        </li>
      </ul>
    </form>
    <p v-if="busy" role="status"><progress /> {{ t("library.working") }}</p>
    <dialog
      v-if="rightsOpen"
      ref="dialog"
      aria-labelledby="rights-title"
      @cancel.prevent="close"
    >
      <h2 id="rights-title">{{ t("community.publish") }}</h2>
      <p>{{ t("community.shareNotice") }}</p>
      <p>{{ t("community.allAssets") }}</p>
      <p v-if="replace">{{ t("community.replaceNotice") }}</p>
      <p v-if="withdrawn" role="status">{{ t("community.partial") }}</p>
      <p v-if="error" role="alert">{{ error }}</p>
      <form @submit.prevent="publish">
        <fieldset v-for="r in rights" :key="r.assetId" :disabled="busy">
          <legend>
            {{ material.files.find((f) => f.id === r.assetId)?.originalName }}
          </legend>
          <label
            ><input
              v-model="r.chosen"
              type="checkbox"
              :disabled="
                material.files.find((f) => f.id === r.assetId)
                  ?.processingState !== 'READY'
              "
            />{{ t("community.include") }}</label
          >
          <label
            >{{ t("community.basis")
            }}<select v-model="r.rightsBasis" :required="r.chosen" :disabled="!r.chosen" @change="basis(r)">
              <option disabled value="">{{ t("community.choose") }}</option>
              <option v-for="b in bases" :key="b" :value="b">
                {{ t("community.bases." + b) }}
              </option>
            </select></label
          >
          <p>
            {{ t("community.provenance") }}:
            {{
              r.provenance ? t("community.provenances." + r.provenance) : "—"
            }}
          </p>
          <label
            >{{ t("community.evidence")
            }}<textarea
              v-model="r.rightsEvidence"
              :required="r.chosen" :disabled="!r.chosen"
              minlength="8"
              maxlength="500"
            />
          </label>
          <label
            >{{ t("community.attribution")
            }}<textarea v-model="r.attribution" :disabled="!r.chosen" maxlength="2000" />
          </label>
        </fieldset>
        <label
          >{{ t("community.tags")
          }}<input v-model="tags" maxlength="410" :disabled="busy"
        /></label>
        <label
          ><input
            v-model="confirm"
            type="checkbox"
            :disabled="busy"
            required
          />{{ t("community.confirm") }}</label
        >
        <button type="button" :disabled="busy" @click="close">
          {{ t("library.cancel") }}
        </button>
        <button :disabled="busy || !confirm">
          {{ t("community.publish") }}
        </button>
        <p v-if="busy" role="status">{{ t("library.working") }}</p>
      </form>
    </dialog>
  </section>
</template>
<style scoped>
.sharing {
  border: 1px solid #dbe8e4;
  border-radius: 18px;
  padding: 24px;
  background: #fff;
  margin-top: 20px;
  overflow-wrap: anywhere;
  min-width: 0;
}
button,
input,
select,
textarea {
  font: inherit;
  min-height: 44px;
  max-width: 100%;
  box-sizing: border-box;
  border: 1px solid #b8c9ca;
  border-radius: 8px;
  padding: 8px;
  background: white;
  color: #285a54;
}
button {
  cursor: pointer;
  margin: 4px;
}
button:disabled {
  opacity: 0.5;
}
label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 12px 0;
  flex-wrap: wrap;
}
label:has(textarea),
label:has(select) {
  align-items: start;
  flex-direction: column;
}
input[type="checkbox"] {
  width: 22px;
  min-height: 22px;
  flex-shrink: 0;
}
textarea {
  width: 100%;
}
fieldset {
  min-width: 0;
  margin: 16px 0;
  border: 1px solid #dbe8e4;
  border-radius: 8px;
}
dialog {
  width: min(600px, calc(100% - 32px));
  max-height: 85dvh;
  overflow: auto;
  border: 1px solid #b8c9ca;
  border-radius: 16px;
  padding: 20px;
  box-sizing: border-box;
}
dialog::backdrop {
  background: #142c2c8c;
}
:focus-visible {
  outline: 3px solid #28776d;
  outline-offset: 2px;
}
summary {
  cursor: pointer;
  min-height: 44px;
  padding: 8px;
}
progress {
  max-width: 120px;
}
</style>
