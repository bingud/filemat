<script lang="ts">
    import { handleErr, safeFetch } from "$lib/code/util/codeUtil.svelte"
    import Loader from "$lib/component/Loader.svelte"
    import { onMount } from "svelte"

    type Status = {
        isAvailable: boolean,
        // "built-in", or the path of the ffmpeg executable
        source: string | null,
        version: string | null,
        problem: string | null,
    }

    let status: Status | null = $state(null)
    let loadError: string | null = $state(null)

    onMount(() => {
        loadStatus()
    })

    async function loadStatus() {
        const response = await safeFetch(`/api/v1/admin/system/video-previews`, { method: `GET` })
        if (response.failed) {
            handleErr({ exception: response.exception })
            loadError = `Failed to load the video preview status.`
            return
        }

        const json = response.json()
        if (response.code.failed) {
            handleErr({ description: `Failed to load the video preview status ${json.message || ``}` })
            loadError = json.message || `Failed to load the video preview status.`
            return
        }

        status = json
    }
</script>

<div class="flex flex-col gap-2">
    <h3 class="font-medium">Video previews</h3>
    {#if status}
        <div class="flex items-center gap-2 my-1">
            <div class={`w-3 h-3 rounded-full ${status.isAvailable ? `bg-green-500` : `bg-red-500`}`}></div>
            <p class="text-base font-medium">{status.isAvailable ? `Enabled` : `Disabled`}</p>
        </div>
        {#if status.isAvailable}
            <p class="text-sm text-neutral-500 break-all">
                {status.source === `built-in` ? `Using the FFmpeg built into Filemat.` : `Using FFmpeg at ${status.source}`}
            </p>
            {#if status.version}
                <p class="text-sm text-neutral-500 break-all">{status.version}</p>
            {/if}
        {:else}
            <p class="text-sm text-neutral-600 dark:text-neutral-400 break-words">{status.problem}</p>
        {/if}
    {:else if loadError}
        <p>{loadError}</p>
    {:else}
        <Loader />
    {/if}
</div>
