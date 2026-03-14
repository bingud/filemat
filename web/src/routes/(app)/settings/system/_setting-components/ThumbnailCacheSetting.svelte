<script lang="ts">
    import { debounceFunction, deepEqual, explicitEffect, formData, handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte"
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte";

    type Options = {
        isEnabled: boolean,
        folderPath: string | null,
        maxSizeMb: number,
        maxAge: number
    }

    let dataLoadError: string | null = $state(null)

    let saving = $state(false)
    let clearingCache = $state(false)

    let originalOptions: Options | null = $state(null)
    let options: Options | null = $state(null)
    let isOptionsEdited = $derived(!deepEqual(options, originalOptions))

    onMount(() => {
        loadData()
    })

    async function saveSettings() {
        if (saving) return
        saving = true

        try {
            const body = formData({})

            const response = await safeFetch(`/api/v1/admin/thumbnails/settings`, { body })

            if (response.failed) {
                handleErr({
                    description: "Failed to save thumbnail settings",
                    notification: "Could not save thumbnail settings",
                })
                return
            }

            const status = response.code
            const json = response.json()

            if (status.failed) {
                handleErr({
                    description: "Failed to save thumbnail settings",
                    notification: json.message || "Failed to save thumbnail settings",
                    isServerDown: status.serverDown,
                })
                return
            }
        } finally {
            saving = false
        }
    }

    async function clearCache() {
        if (clearingCache) return
        clearingCache = true

        try {
            const response = await safeFetch(`/api/v1/admin/thumbnails/clear-cache`, {
                body: formData({}),
            })

            if (response.failed || response.code.failed) {
                handleErr({
                    description: "Failed to clear thumbnail cache",
                    notification: "Could not clear thumbnail cache",
                })
                return
            }
        } finally {
            clearingCache = false
        }
    }

    async function loadData() {
        const response = await safeFetch(`/api/v1/admin/system/thumbnails`, { method: "GET" })
        if (response.failed) {
            handleErr({ exception: response.exception })
            dataLoadError = "Failed to load thumbnail settings."
            return
        }

        const json = response.json()
        if (response.code.failed) {
            handleErr({ description: `Failed to load thumbnail cache settings ${json.message || ""}` })
            dataLoadError = json.message || `Failed to load thumbnail settings.`
            return
        }

        originalOptions = json
        options = json
        dataLoadError = null
    }

    function cancel() {
        options = originalOptions
    }
</script>

<div class="flex flex-col gap-2">
    <h3 class="font-medium">Thumbnail caching</h3>
    {#if options && originalOptions}
        <div class="flex flex-col gap-6">
            <div class="flex flex-col gap-2">
                <div class="flex items-center gap-2 my-1">
                    <div class={`w-3 h-3 rounded-full ${options.isEnabled ? "bg-green-500" : "bg-neutral-500"}`}></div>
                    <p class="text-base font-medium">{options.isEnabled ? "Enabled" : "Disabled"}</p>
                </div>

                <div class="flex gap-2">
                    <button
                        on:click={() => {}}
                        class="basic-button bg-surface-content-button!"
                    >
                        {options.isEnabled ? "Disable" : "Enable"}
                    </button>
                    <button
                        disabled={clearingCache}
                        on:click={clearCache}
                        class="basic-button bg-surface-content-button! disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {clearingCache ? "Clearing..." : "Clear cache"}
                    </button>
                </div>
            </div>

            <hr class="border-neutral-300 dark:border-neutral-700 w-[15rem] max-w-full" />

            <div class="flex flex-col gap-4">
                <div class="flex flex-col gap-1">
                    <label class="text-sm font-medium" for="cache-folder">Cache folder</label>
                    <input
                        id="cache-folder"
                        type="text"
                        bind:value={options.folderPath}
                        class="basic-input max-w-full w-[35rem]"
                    />
                </div>

                <div class="flex flex-col gap-1">
                    <label class="text-sm font-medium" for="max-size">Max size (MB)</label>
                    <input
                        id="max-size"
                        type="number"
                        bind:value={options.maxSizeMb}
                        class="basic-input w-36"
                    />
                </div>

                <div class="flex flex-col gap-1">
                    <label class="text-sm font-medium" for="expiration">Thumbnail expiration (hours)</label>
                    <input
                        id="expiration"
                        type="number"
                        bind:value={options.maxAge}
                        class="basic-input w-36"
                    />
                </div>
            </div>

            {#if isOptionsEdited}
                <hr class="border-neutral-300 dark:border-neutral-700" />

                <div class="flex gap-3">
                    <button
                        disabled={saving}
                        on:click={saveSettings}
                        class="basic-button bg-surface-content-button! disabled:opacity-50 disabled:cursor-not-allowed"
                    >
                        {saving ? "Saving..." : "Save"}
                    </button>
                    <button
                        on:click={cancel}
                        class="basic-button bg-surface-button"
                    >
                        Cancel
                    </button>
                </div>
            {/if}
        </div>
    {:else if dataLoadError}
        <p>{dataLoadError}</p>
    {:else}
        <Loader />
    {/if}
</div>