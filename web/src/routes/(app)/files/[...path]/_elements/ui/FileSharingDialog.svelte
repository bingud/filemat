<script lang="ts">
    import type { FileShare, FileSharePublic } from "$lib/code/auth/types";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import { explicitEffect, formData, handleErr, handleException, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { useReplaceChars } from "$lib/code/util/uiUtil";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import PlusIcon from "$lib/component/icons/PlusIcon.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { Dialog } from "bits-ui";
    import { onMount } from "svelte";

    let {
        open = $bindable(),
    }: {
        open: boolean,
    } = $props()

    let shares: FileSharePublic[] | null = $state(null)
    let creatingFormOpen = $state(false)

    let linkInput = $state('')
    let maxAgeInput: number | undefined = $state()
    let maxAgeUnit: string = $state('hours')
    let maxAgeEnabled = $state(true)

    let passwordEnabled = $state(false)
    let passwordInput = $state('')

    let isCreating = $state(false)

    onMount(() => {})

    explicitEffect(() => [open], () => {
        if (open) {
            loadShares()
        } else {
            cancelCreating()
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
            body: formData({ path: filesState.path })
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

        shares = json
    }

    async function createShare() {
        if (!linkInput) return

        const body = formData({
            path: filesState.path,
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
        console.log(expiryTime)
        const remainingMs = Math.max(0, expiryTime * 1000 - Date.now())
        
        const days = Math.floor(remainingMs / (1000 * 60 * 60 * 24))
        const hours = Math.floor((remainingMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60))
        const minutes = Math.floor((remainingMs % (1000 * 60 * 60)) / (1000 * 60))
        const seconds = Math.floor((remainingMs % (1000 * 60)) / 1000)
        
        if (days > 0) return `${days} ${days === 1 ? 'day' : 'days'}`
        if (hours > 0) return `${hours} ${hours === 1 ? 'hour' : 'hours'}`
        if (minutes > 0) return `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`
        if (seconds > 0) return `${seconds} ${seconds === 1 ? 'second' : 'seconds'}`
        return 'now'
    }
</script>


<Dialog.Root bind:open={open}>
    <Dialog.Trigger>
        {#snippet child({props})}
            <button {...props} title="File sharing" class="h-[2.5rem] w-fit px-3 rounded-md bg-surface-button disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-150 shadow-sm">
                File sharing
            </button>
        {/snippet}
    </Dialog.Trigger>

    <Dialog.Portal>
        <Dialog.Overlay
            class="fixed inset-0 z-50 bg-black/50"
        />
        <Dialog.Content>
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
                        <button on:click={() => { creatingFormOpen = true }} class="basic-button !bg-surface-button flex gap-2">
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
                                <button on:click={createShare} disabled={isCreating} class="basic-button !bg-surface-button hover:ring-2 ring-green-500">{isCreating ? 'Creating...' : 'Create'}</button>
                                <button on:click={cancelCreating} class="basic-button !bg-surface-button hover:ring-2 ring-red-500">Cancel</button>
                            </div>
                        </form>
                    {/if}

                    <div class="flex flex-col rounded-lg bg-bg w-full h-fit p-2 gap-2">
                        {#if shares}
                            {#if shares.length > 0}
                                {#each shares as share}
                                    {@const expirationDate = share.maxAge ? share.createdDate + share.maxAge : null}

                                    <button class="w-full p-3 rounded-md bg-surface hover:bg-surface-button text-left">
                                        <h4 class="font-medium text-sm truncate mb-4">{share.shareId}</h4>
                                        
                                        <div class="flex gap-6">
                                            <div class="flex-1">
                                                <div class="grid grid-cols-2 gap-6 text-xs">
                                                    <div>
                                                        <p class="text-neutral-500 dark:text-neutral-400 mb-1">Created</p>
                                                        <p class="font-medium">
                                                            {new Date(share.createdDate * 1000).toLocaleString('en-GB', {
                                                                year: 'numeric',
                                                                month: '2-digit',
                                                                day: '2-digit',
                                                                hour: '2-digit',
                                                                minute: '2-digit',
                                                                second: '2-digit',
                                                                hour12: false
                                                            })}
                                                        </p>
                                                    </div>
                                                    
                                                    {#if expirationDate}
                                                        <div>
                                                            <p class="text-neutral-500 dark:text-neutral-400 mb-1">Expires in: {getTimeRemaining(share.createdDate + share.maxAge)}</p>
                                                            <p class="font-medium">
                                                                {new Date((expirationDate) * 1000).toLocaleString('en-GB', {
                                                                    year: 'numeric',
                                                                    month: '2-digit',
                                                                    day: '2-digit',
                                                                    hour: '2-digit',
                                                                    minute: '2-digit',
                                                                    second: '2-digit',
                                                                    hour12: false
                                                                })}
                                                            </p>
                                                        </div>
                                                    {/if}
                                                </div>
                                            </div>
                                            
                                            <div class="flex flex-col gap-2 flex-shrink-0 text-xs">
                                                {#if share.isPassword}
                                                    <span class="px-1.5 py-0.5 rounded bg-green-500/20 text-green-700 dark:text-green-400 text-center">
                                                        Password
                                                    </span>
                                                {:else}
                                                    <span class="px-1.5 py-0.5 rounded bg-neutral-500/20 text-neutral-700 dark:text-neutral-400 text-center">
                                                        No password
                                                    </span>
                                                {/if}
                                                
                                                <a href="/user/{share.userId}" class="text-blue-600 dark:text-blue-400 hover:underline">
                                                    User Account
                                                </a>
                                            </div>
                                        </div>
                                    </button>
                                {/each}
                            {:else}
                                <p class="text-center">You haven't shared this file.</p>
                            {/if}
                        {:else}
                            <Loader class="m-auto"></Loader>
                        {/if}
                    </div>
                </div>
            </div>
        </Dialog.Content>
    </Dialog.Portal>
</Dialog.Root>