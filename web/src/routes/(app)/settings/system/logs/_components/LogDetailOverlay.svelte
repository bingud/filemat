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

        window.history.pushState({ logDetailId: id }, '')

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


<div class="fixed inset-0 w-screen h-svh bg-bg z-50 flex flex-col">
    <div class="flex-1 overflow-y-auto p-4 md:p-6 flex flex-col gap-6">
        <div class="flex flex-col gap-4">
            <button
                on:click={handleBackClick}
                class="basic-button ring ring-white/30 w-fit"
                aria-label="Go back"
            >
                Back
            </button>
            
            <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                <div>
                    <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">Type</p>
                    <div class="bg-surface rounded-md px-3 py-2">
                        <p class="text-sm md:text-base font-medium text-neutral-100">{log.type}</p>
                    </div>
                </div>
                <div>
                    <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">Severity</p>
                    <div class="bg-surface rounded-md px-3 py-2">
                        <span
                            class="px-2 py-1 rounded text-xs font-semibold inline-block"
                            class:bg-blue-500={log.level === "DEBUG"}
                            class:bg-green-500={log.level === "INFO"}
                            class:bg-yellow-500={log.level === "WARN"}
                            class:bg-red-500={log.level === "ERROR"}
                            class:bg-purple-500={log.level === "FATAL"}
                        >
                            {log.level}
                        </span>
                    </div>
                </div>
                <div>
                    <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">Action</p>
                    <div class="bg-surface rounded-md px-3 py-2">
                        <p class="text-sm md:text-base font-medium text-neutral-100">{log.action}</p>
                    </div>
                </div>
            </div>
        </div>

        <div>
            <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">Description</p>
            <div class="bg-surface rounded-md px-3 py-2">
                <p class="text-sm text-neutral-200">{log.description}</p>
            </div>
        </div>

        <div>
            <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">Details</p>
            <div class="bg-surface rounded-md px-3 py-2 flex flex-col gap-3 text-sm">
                <div class="flex justify-between gap-4">
                    <span class="text-neutral-400">Date:</span>
                    <span class="text-neutral-200">{new Date(log.createdDate * 1000).toLocaleString('en-GB', { hour12: false })}</span>
                </div>
                <div class="flex flex-col sm:flex-row sm:justify-between gap-2 sm:gap-4">
                    <span class="text-neutral-400">Log ID:</span>
                    <span class="text-neutral-200 font-mono break-all">{log.logId}</span>
                </div>
            </div>
        </div>

        {#if log.initiatorId || log.targetId}
            <div>
                <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">Related IDs</p>
                <div class="bg-surface rounded-md px-3 py-2 flex flex-col gap-3 text-sm">
                    {#if log.initiatorId}
                        <div class="flex flex-col sm:flex-row sm:justify-between gap-2 sm:gap-4">
                            <span class="text-neutral-400">Initiator:</span>
                            <span class="text-neutral-200 font-mono break-all">{log.initiatorId}</span>
                        </div>
                    {/if}
                    {#if log.targetId}
                        <div class="flex flex-col sm:flex-row sm:justify-between gap-2 sm:gap-4">
                            <span class="text-neutral-400">Target:</span>
                            <span class="text-neutral-200 font-mono break-all">{log.targetId}</span>
                        </div>
                    {/if}
                </div>
            </div>
        {/if}

        {#if log.initiatorIp}
            <div>
                <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">IP Address</p>
                <div class="bg-surface rounded-md px-3 py-2">
                    <p class="text-sm font-mono text-neutral-200">{log.initiatorIp}</p>
                </div>
            </div>
        {/if}

        {#if log.metadata && Object.keys(log.metadata).length > 0}
            <div>
                <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">Metadata</p>
                <div class="bg-surface rounded-md px-3 py-2 flex flex-col gap-3">
                    {#each Object.entries(log.metadata) as [key, value]}
                        <div class="flex flex-col gap-1">
                            <span class="text-xs text-neutral-400 font-mono">{key}</span>
                            <span class="text-sm text-neutral-200 font-mono break-all">{value}</span>
                        </div>
                    {/each}
                </div>
            </div>
        {/if}

        <div>
            <p class="text-xs text-blue-300 uppercase tracking-wide font-semibold mb-2">Message</p>
            <pre class="text-sm text-neutral-200 bg-surface rounded-md p-3 overflow-x-auto font-mono">{log.message || "No message."}</pre>
        </div>
    </div>
</div>