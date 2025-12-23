<script lang="ts">
    import type { FileShare } from "$lib/code/auth/types";
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import { createLink, explicitEffect, formData, handleErr, handleException, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { useReplaceChars } from "$lib/code/util/uiUtil";
    import { ContextMenu } from "$lib/component/bits-ui-wrapper";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import PlusIcon from "$lib/component/icons/PlusIcon.svelte";
    import TrashIcon from "$lib/component/icons/TrashIcon.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import Tooltip from "$lib/component/popover/Tooltip.svelte";
    import { CopyIcon } from "@lucide/svelte";
    import { Dialog } from "bits-ui";
    import FileShareItemButton from "./FileShareItemButton.svelte";

    let {
        open = $bindable(),
        path
    }: {
        open: boolean,
        path: string,
    } = $props()

    let shares: FileShare[] | null = $state(null)
    let creatingFormOpen = $state(false)

    let linkInput = $state('')
    let maxAgeInput: number | undefined = $state()
    let maxAgeUnit: string = $state('hours')
    let maxAgeEnabled = $state(true)

    let passwordEnabled = $state(false)
    let passwordInput = $state('')

    let isCreating = $state(false)

    // Context menu
    let contextMenuOpen = $state(false)

    explicitEffect(() => [open], () => {
        if (open) {
            loadShares()
        } else {
            cancelCreating()
            shares = null
        }
    })

    function cancelCreating() {
        creatingFormOpen = false
        linkInput = ''

        maxAgeInput = undefined
        maxAgeUnit = 'hour'
        maxAgeEnabled = true

        passwordEnabled = false
        passwordInput = ''
    }

    async function loadShares() {
        const response = await safeFetch(`/api/v1/file/share/get`, {
            body: formData({ path: path })
        })
        if (response.failed) {
            handleException(
                `Failed to load file shares.`,
                `Failed to load file shares.`,
                response.exception
            )
            return
        }

        const json = response.json()
        if (response.code.failed) {
            handleErr({
                description: `Failed to load file shares`,
                notification: json.message || `Failed to load file shares`,
                isServerDown: response.code.serverDown
            })
            return
        }

        const sorted = (json as typeof shares)!.sort((a, b) => {
            return b.createdDate - a.createdDate
        })
        shares = sorted
    }

    async function createShare() {
        if (!linkInput) return

        const body = formData({
            path: path,
            sharePath: linkInput,
        })

        if (maxAgeEnabled) {
            if (!maxAgeInput || !maxAgeUnit) return
            body.append("maxAge", durationToSeconds(maxAgeInput, maxAgeUnit).toString())
        }
        if (passwordEnabled) {
            if (!passwordInput) return
            body.append("password", passwordInput)
        }

        isCreating = true
        const response = await safeFetch(`/api/v1/file/share/create`, {
            body: body
        })
        isCreating = false

        if (response.failed) {
            handleException(
                `Fetch exception while sharing file.`,
                `Failed to share this file.`,
                response.exception
            )
            return
        }
        
        const json = response.json()
        if (response.code.failed) {
            handleErr({
                description: `Failed to share file.`,
                notification: json.message || `Failed to share this file.`,
                isServerDown: response.code.serverDown
            })
            return
        }

        if (shares) shares.push(json)
        cancelCreating()
    }

    function replaceUrlChar(c: string): string {
        if (c === " ") return "-"
        if (/[a-z0-9\-_.~]/i.test(c)) return c
        return ""
    }

    function durationToSeconds(value: number, unit: string): number {
        const conversions = {
            minutes: 60,
            hours: 60 * 60,
            days: 24 * 60 * 60,
            weeks: 7 * 24 * 60 * 60,
            months: 30 * 24 * 60 * 60
        }
        
        return value * conversions[unit as keyof typeof conversions]
    }

    function getTimeRemaining(expiryTime: number) {
        const ms = Math.max(0, expiryTime * 1000 - Date.now())

        const msMinute = 1000 * 60
        const msHour = msMinute * 60
        const msDay = msHour * 24
        const avgMonthDays = 30.436875

        const days = Math.floor(ms / msDay)
        const months = Math.floor(days / avgMonthDays)

        if (months > 0) return `${months} ${months === 1 ? 'month' : 'months'}`
        if (days > 0) return `${days} ${days === 1 ? 'day' : 'days'}`

        const hours = Math.floor((ms % msDay) / msHour)
        if (hours > 0) return `${hours} ${hours === 1 ? 'hour' : 'hours'}`

        const minutes = Math.floor((ms % msHour) / msMinute)
        if (minutes > 0) return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`

        const seconds = Math.floor((ms % msMinute) / 1000)
        if (seconds > 0) return `${seconds} ${seconds === 1 ? 'second' : 'seconds'}`

        return 'now'
    }

    async function deleteShare(share: FileShare) {
        const confirmation = await confirmDialogState.show({
            title: `Delete file share`,
            message: `Do you want to delete this file share? This public link will become unavailable.`,
        })
        if (!confirmation) return

        const response = await safeFetch(`/api/v1/file/share/delete`, {
            body: formData({ shareId: share.shareId, entityId: share.fileId })
        })

        if (response.failed) {
            handleException(
                `Fetch exception while deleting file share.`,
                `Failed to delete this share.`,
                response.exception
            )
            return
        }
        
        const json = response.json()
        if (response.code.failed) {
            handleErr({
                description: `Failed to delete file share.`,
                notification: json.message || `Failed to delete this file share.`,
                isServerDown: response.code.serverDown
            })
            return
        }

        if (shares) {
            const index = shares.findIndex((v) => { v === share })
            shares.splice(index, 1)
        }
    }
</script>


<Dialog.Root bind:open={open}>
    <Dialog.Trigger>
        {#snippet child({props})}
            <button {...props} title="File sharing" class="h-[2.5rem] w-fit px-3 rounded-md bg-surface-content-button disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-150 shadow-sm">
                File sharing
            </button>
        {/snippet}
    </Dialog.Trigger>

    <Dialog.Portal>
        <Dialog.Overlay onclick={() => { open = false }}
            class="fixed inset-0 z-50 bg-black/50"
        />
        <Dialog.Content interactOutsideBehavior="ignore">
            <div class="rounded-lg bg-surface shadow-popover fixed left-[50%] top-[50%] z-50 w-[30rem] max-w-[calc(100%-2rem)] translate-x-[-50%] translate-y-[-50%] p-5 flex flex-col gap-12">
                <div class="flex items-center justify-between w-full">
                    <h3>File sharing</h3>
                    <Dialog.Close>
                        <div class="rounded-md hover:bg-neutral-300 dark:hover:bg-neutral-700 h-[2.5rem] aspect-square p-2">
                            <CloseIcon></CloseIcon>
                        </div>
                    </Dialog.Close>
                </div>

                <div class="flex flex-col gap-12">
                    {#if !creatingFormOpen}
                        <button on:click={() => { creatingFormOpen = true }} class="basic-button !bg-surface-content-button flex gap-2">
                            <div class="size-[1.5rem]"><PlusIcon/></div>
                            Create a public link
                        </button>
                    {:else}
                        <form class="flex flex-col gap-4">
                            <div class="flex flex-col gap-1">
                                <label for="id-input">Public file link</label>
                                <input use:useReplaceChars={replaceUrlChar} bind:value={linkInput} id="id-input" class="basic-input">
                            </div>

                            <!-- Expiration -->
                            <div class="flex flex-col gap-1">
                                <div class="flex gap-1 items-center">
                                    <input bind:checked={maxAgeEnabled} type="checkbox" class="!opacity-100">
                                    <label for="duration-input">Expiration time</label>
                                </div>
                                {#if maxAgeEnabled}
                                    <div class="flex gap-2">
                                        <input bind:value={maxAgeInput} id="duration-input" type="number" class="basic-input flex-1" min="1">
                                        <select bind:value={maxAgeUnit} class="basic-input bg-bg">
                                            <option value="minutes">Minutes</option>
                                            <option value="hours">Hours</option>
                                            <option value="days">Days</option>
                                            <option value="weeks">Weeks</option>
                                            <option value="months">Months</option>
                                        </select>
                                    </div>
                                {/if}
                            </div>

                            <!-- Password -->
                            <div class="flex flex-col gap-1">
                                <div class="flex gap-1 items-center">
                                    <input bind:checked={passwordEnabled} type="checkbox" class="!opacity-100">
                                    <label for="password-input">Password</label>
                                </div>
                                {#if passwordEnabled}
                                    <div class="flex gap-2">
                                        <input bind:value={passwordInput} id="password-input" type="password" class="basic-input flex-1">
                                    </div>
                                {/if}
                            </div>
                            
                            <div>
                                <button on:click={createShare} disabled={isCreating} class="basic-button !bg-surface-content-button hover:ring-2 ring-green-500">{isCreating ? 'Creating...' : 'Create'}</button>
                                <button on:click={cancelCreating} class="basic-button !bg-surface-content-button hover:ring-2 ring-red-500">Cancel</button>
                            </div>
                        </form>
                    {/if}

                    <div class="flex flex-col rounded-lg bg-bg w-full h-fit p-2 gap-2">
                        {#if shares}
                            {#each shares as share}
                                <FileShareItemButton
                                    {share} 
                                    {deleteShare} 
                                    {getTimeRemaining} 
                                />
                            {:else}
                                <p class="text-center">You haven't shared this file.</p>
                            {/each}
                        {:else}
                            <Loader class="m-auto"></Loader>
                        {/if}
                    </div>
                </div>
            </div>
        </Dialog.Content>
    </Dialog.Portal>
</Dialog.Root>