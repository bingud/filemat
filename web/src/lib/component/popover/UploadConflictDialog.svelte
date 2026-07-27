<script lang="ts">
    import { uploadConflictDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte"
    import CustomDialog from "$lib/component/popover/CustomDialog.svelte"

    type UploadConflictResolution = "overwrite" | "skip" | "keep-both"

    type UploadConflictDialogConflict = {
        relativePath: string,
        targetPath: string,
        incomingType: "file" | "directory" | "other",
        existingType: "file" | "directory" | "other",
        allowedResolutions: UploadConflictResolution[],
        message?: string | null,
    }

    let conflicts: UploadConflictDialogConflict[] = $state([])
    let resolutions: Record<string, UploadConflictResolution> = $state({})
    // Do not put the resolver in $state — Svelte 5 state proxies break function values.
    let resolvePromise: ((value: Record<string, UploadConflictResolution> | null) => void) | null = null
    let visibleLimit = $state(50)
    let dialogTitle = $state(`1 file already exists`)

    export function show(options: {
        conflicts: UploadConflictDialogConflict[],
        title?: string,
    }): Promise<Record<string, UploadConflictResolution> | null> {
        // Settle any previous open dialog so a stale close event cannot resolve the new promise.
        resolvePromise?.(null)
        resolvePromise = null

        conflicts = options.conflicts
        resolutions = {}
        visibleLimit = 50

        const count = options.conflicts.length
        dialogTitle = options.title
            || (count === 1 ? `1 file already exists` : `${count} files already exist`)

        for (const conflict of conflicts) {
            resolutions[conflict.relativePath] = conflict.allowedResolutions.includes(`keep-both`)
                ? `keep-both`
                : conflict.allowedResolutions[0]
        }

        uploadConflictDialogState.isOpen = true

        return new Promise((resolve) => {
            resolvePromise = resolve
        })
    }

    function setResolution(path: string, resolution: UploadConflictResolution) {
        resolutions[path] = resolution
    }

    function applyToAll(resolution: UploadConflictResolution) {
        for (const conflict of conflicts) {
            if (conflict.allowedResolutions.includes(resolution)) {
                resolutions[conflict.relativePath] = resolution
            }
        }
    }

    function settle(value: Record<string, UploadConflictResolution> | null) {
        const resolve = resolvePromise
        resolvePromise = null
        uploadConflictDialogState.isOpen = false
        resolve?.(value)
    }

    function confirm() {
        settle({ ...resolutions })
    }

    function cancel() {
        settle(null)
    }

    function handleClose(open: boolean) {
        // Ignore open transitions and stale closes after settle().
        if (open || !resolvePromise) return
        settle(null)
    }

    function resolutionButtonClass(isSelected: boolean) {
        return `basic-button ${isSelected ? "bg-surface-content-button! ring-1 ring-neutral-400 dark:ring-neutral-500" : ""}`
    }

    let canOverwriteAny = $derived(conflicts.some(conflict => conflict.allowedResolutions.includes(`overwrite`)))
</script>

<CustomDialog
    bind:isOpen={uploadConflictDialogState.isOpen}
    onOpenChange={handleClose}
    title={dialogTitle}
    class="w-3xl!"
>
    <div class="flex flex-col gap-4 min-h-0">
        <div class="flex flex-col gap-2">
            <div class="flex flex-wrap gap-2">
                <button class="basic-button bg-surface-content-button!" on:click={() => applyToAll(`keep-both`)}>Keep both for all</button>
                <button class="basic-button bg-surface-content-button!" on:click={() => applyToAll(`skip`)}>Skip all</button>
                {#if canOverwriteAny}
                    <button class="basic-button bg-surface-content-button!" on:click={() => applyToAll(`overwrite`)}>Overwrite all</button>
                {/if}
            </div>
        </div>

        <hr class="basic-hr">

        <div class="max-h-[50vh] overflow-auto custom-scrollbar flex flex-col gap-2">
            {#each conflicts.slice(0, visibleLimit) as conflict}
                <div class="surface-content rounded p-3 flex flex-col gap-2">
                    <div class="min-w-0">
                        <p class="font-medium truncate">{conflict.relativePath}</p>
                        {#if conflict.message}
                            <p class="text-sm opacity-70 mt-1">{conflict.message}</p>
                        {/if}
                    </div>

                    <div class="flex flex-wrap gap-2">
                        {#if conflict.allowedResolutions.includes(`keep-both`)}
                            <button
                                class={resolutionButtonClass(resolutions[conflict.relativePath] === `keep-both`)}
                                on:click={() => setResolution(conflict.relativePath, `keep-both`)}
                            >
                                Keep both
                            </button>
                        {/if}
                        {#if conflict.allowedResolutions.includes(`skip`)}
                            <button
                                class={resolutionButtonClass(resolutions[conflict.relativePath] === `skip`)}
                                on:click={() => setResolution(conflict.relativePath, `skip`)}
                            >
                                Skip
                            </button>
                        {/if}
                        {#if conflict.allowedResolutions.includes(`overwrite`)}
                            <button
                                class={resolutionButtonClass(resolutions[conflict.relativePath] === `overwrite`)}
                                on:click={() => setResolution(conflict.relativePath, `overwrite`)}
                            >
                                Overwrite
                            </button>
                        {/if}
                    </div>
                </div>
            {/each}
        </div>

        {#if conflicts.length > visibleLimit}
            <button class="basic-button self-start" on:click={() => visibleLimit += 50}>
                Show 50 more
            </button>
        {/if}

        <div class="flex justify-end gap-2">
            <button class="basic-button" on:click={cancel}>Cancel</button>
            <button class="basic-button bg-surface-content-button!" on:click={confirm}>Start upload</button>
        </div>
    </div>
</CustomDialog>
