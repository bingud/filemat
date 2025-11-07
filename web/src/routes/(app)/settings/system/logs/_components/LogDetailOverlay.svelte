<script lang="ts">
    import type { Log } from "./logs"

    let {
        log,
        onClose
    }: {
        log: Log,
        onClose: () => any
    } = $props()

    let historyId = $state<string | null>(null)

    $effect(() => {
        const id = Math.random().toString(36).slice(2, 11)
        historyId = id

        window.history.pushState({ logDetailId: id }, '', window.location.href)

        const handlePopState = (e: PopStateEvent) => {
            if (e.state?.logDetailId !== id) {
                onClose()
            }
        }

        window.addEventListener('popstate', handlePopState)

        return () => {
            window.removeEventListener('popstate', handlePopState)
        }
    })

    const handleBackClick = () => {
        window.history.back()
    }
</script>

<div class="fixed inset-0 w-screen h-svh bg-surface z-50 flex flex-col">
    <div class="flex-1 overflow-y-auto p-6 flex flex-col gap-6">
        <div class="flex items-center justify-between gap-4">
            <div class="flex items-end gap-6">
                <button
                    on:click={handleBackClick}
                    class="basic-button"
                    aria-label="Go back"
                >
                    Back
                </button>
                <div>
                    <p class="text-xs text-neutral-400 uppercase tracking-wide mb-1">Type</p>
                    <p class="text-base font-medium">{log.type}</p>
                </div>
                <div>
                    <p class="text-xs text-neutral-400 uppercase tracking-wide mb-1">Severity</p>
                    <span
                        class="px-2 py-1 rounded text-xs font-medium"
                        class:bg-blue-500={log.level === "DEBUG"}
                        class:bg-green-500={log.level === "INFO"}
                        class:bg-yellow-500={log.level === "WARN"}
                        class:bg-red-500={log.level === "ERROR"}
                        class:bg-purple-500={log.level === "FATAL"}
                    >
                        {log.level}
                    </span>
                </div>
                <div>
                    <p class="text-xs text-neutral-400 uppercase tracking-wide mb-1">Action</p>
                    <p class="text-base font-medium">{log.action}</p>
                </div>
            </div>
            <button
                on:click={onClose}
                class="basic-button"
                aria-label="Close log details"
            >
                Close
            </button>
        </div>

        <div>
            <p class="text-xs text-neutral-400 uppercase tracking-wide mb-1">Description</p>
            <p class="text-sm text-neutral-300">{log.description}</p>
        </div>

        <div class="w-fit">
            <p class="text-xs text-neutral-400 uppercase tracking-wide mb-2">Details</p>
            <div class="flex flex-col gap-2 text-sm">
                <div class="flex justify-between gap-4">
                    <span class="text-neutral-400">Date:</span>
                    <span class="text-neutral-300">{new Date(log.createdDate * 1000).toLocaleString('en-GB', { hour12: false })}</span>
                </div>
                <div class="flex justify-between gap-4">
                    <span class="text-neutral-400">Log ID:</span>
                    <span class="text-neutral-300 font-mono break-all">{log.logId}</span>
                </div>
            </div>
        </div>

        {#if log.initiatorId || log.targetId}
            <div class="w-fit">
                <p class="text-xs text-neutral-400 uppercase tracking-wide mb-2">Related IDs</p>
                <div class="flex flex-col gap-2 text-sm">
                    {#if log.initiatorId}
                        <div class="flex justify-between gap-4">
                            <span class="text-neutral-400">Initiator:</span>
                            <span class="text-neutral-300 font-mono break-all">{log.initiatorId}</span>
                        </div>
                    {/if}
                    {#if log.targetId}
                        <div class="flex justify-between gap-4">
                            <span class="text-neutral-400">Target:</span>
                            <span class="text-neutral-300 font-mono break-all">{log.targetId}</span>
                        </div>
                    {/if}
                </div>
            </div>
        {/if}

        {#if log.initiatorIp}
            <div class="w-fit">
                <p class="text-xs text-neutral-400 uppercase tracking-wide mb-1">IP Address</p>
                <p class="text-sm font-mono text-neutral-300">{log.initiatorIp}</p>
            </div>
        {/if}

        {#if log.metadata && Object.keys(log.metadata).length > 0}
            <div class="w-fit">
                <p class="text-xs text-neutral-400 uppercase tracking-wide mb-2">Metadata</p>
                <div class="bg-surface/50 rounded p-3 flex flex-col gap-2">
                    {#each Object.entries(log.metadata) as [key, value]}
                        <div class="flex flex-col gap-1">
                            <span class="text-xs text-neutral-400 font-mono">{key}</span>
                            <span class="text-sm text-neutral-300 font-mono break-all">{value}</span>
                        </div>
                    {/each}
                </div>
            </div>
        {/if}

        <div class="w-fit">
            <p class="text-xs text-neutral-400 uppercase tracking-wide mb-1">Message</p>
            <pre class="text-sm text-neutral-300 bg-surface/50 p-2 rounded overflow-x-auto">{log.message || "No message."}</pre>
        </div>
    </div>
</div>