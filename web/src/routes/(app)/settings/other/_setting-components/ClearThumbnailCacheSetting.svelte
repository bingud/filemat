<script lang="ts">
    import { setPreferenceSetting } from "$lib/code/module/settings";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import { safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { toast } from "@jill64/svelte-toast";

    let running = $state(false)
    async function clearCache() {
        if (running) return
        running = true

        try {
            const conf = await confirmDialogState.show({
                title: "Clear preview cache", 
                message: "Are you sure you want to clear all file preview cache?", 
                confirmText: "Yes", 
                cancelText: "Cancel"
            })
            if (!conf) return

            const response = await safeFetch(`/__filemat-clear-sw-thumb-cache`, { method: "GET" })
            if (response.code.failed) {
                toast.error(`Failed to clear preview cache: ${response.status}`)
            } else {
                toast.success(`Preview cache was cleared.`)
            }
        } finally {
            running = false
        }
    }
</script>




<div class="flex flex-col gap-4">
    <div class="flex flex-col gap-2">
        <h3 class="font-medium">Clear image preview cache</h3>
    </div>

    <p class="text-sm text-neutral-600 dark:text-neutral-400">
        This only clears preview cache from your browser that Filemat saved.<br>It will not clear external cache or your browser's automatic image cache (if you enabled cache control).
    </p>

    <button
        on:click={clearCache} 
        class="mt-2 w-fit px-4 py-2 bg-surface-content-button rounded-md">
        Clear preview cache
    </button>
</div>