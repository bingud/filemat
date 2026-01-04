<script lang="ts">
    import type { FileShare } from "$lib/code/auth/types"
    import { ContextMenu } from "$lib/component/bits-ui-wrapper"
    import TrashIcon from "$lib/component/icons/TrashIcon.svelte"
    import Tooltip from "$lib/component/popover/Tooltip.svelte"
    import { createLink } from "$lib/code/util/codeUtil.svelte"
    import CopyIcon from "$lib/component/icons/CopyIcon.svelte";


    let {
        share,
        deleteShare,
        getTimeRemaining
    }: {
        share: FileShare
        deleteShare: (share: FileShare) => void
        getTimeRemaining: (expiryTime: number) => string
    } = $props()

    let contextMenuOpen = $state(false)
    let expirationDate = $derived(share.maxAge ? share.createdDate + share.maxAge : null)
</script>



<ContextMenu.Root bind:open={contextMenuOpen}>
    <ContextMenu.Trigger>
        {#snippet child({props})}
            <button {...props} on:click={() => { contextMenuOpen = true }} class="w-full p-3 rounded-md bg-surface hover:bg-surface-content-button text-left">
                <Tooltip text={share.shareId} align="start">
                    <h4 class="font-medium text-sm truncate mb-4">{share.shareId}</h4>
                </Tooltip>
                
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
                                        hour12: false,
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
                                            hour12: false
                                        })}
                                    </p>
                                </div>
                            {/if}
                        </div>
                    </div>
                    
                    <div class="flex flex-col items-end gap-2 flex-shrink-0 text-xs">
                        {#if share.isPassword}
                            <span class="px-1.5 py-0.5 rounded bg-green-500/20 text-green-700 dark:text-green-400 text-center">
                                Password
                            </span>
                        {:else}
                            <span class="px-1.5 py-0.5 rounded bg-neutral-500/20 text-neutral-700 dark:text-neutral-400 text-center">
                                No password
                            </span>
                        {/if}
                        
                        <a on:click|stopPropagation={() => {}} target="_blank" href="/settings/users/{share.userId}" class="text-blue-600 dark:text-blue-400 hover:underline">
                            User Account
                        </a>
                    </div>
                </div>
            </button>
        {/snippet}
    </ContextMenu.Trigger>

    <ContextMenu.Content>
        <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50 select-none">
            <button on:click={() => { navigator.clipboard.writeText(createLink(`/share/${share.shareId}`)); contextMenuOpen = false }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                <div class="size-5 flex-shrink-0">
                    <CopyIcon />
                </div>
                <span>Copy link</span>
            </button>
            <button on:click={() => { deleteShare(share) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                <div class="size-5 flex-shrink-0">
                    <TrashIcon />
                </div>
                <span>Delete</span>
            </button>
            <hr class="basic-hr my-2">
            <p class="px-4 truncate opacity-70">Share: {share.shareId}</p>
        </div>
    </ContextMenu.Content>
</ContextMenu.Root>