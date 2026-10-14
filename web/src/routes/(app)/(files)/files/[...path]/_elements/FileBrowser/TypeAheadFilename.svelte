<script lang="ts">
    import { filesState } from "$lib/code/stateObjects/filesState.svelte"
    import type { FileMetadata } from "$lib/code/auth/types"

    let { entry }: { entry: FileMetadata } = $props()

    let filename = $derived(entry.filename ?? ``)
    let highlight = $derived.by(() => {
        const typeAhead = filesState.typeAhead
        if (!typeAhead.matchSet.has(entry.path)) return null

        const start = filename.toLowerCase().indexOf(typeAhead.query.toLowerCase())
        if (start === -1) return null
        return { start, end: start + typeAhead.query.length }
    })
</script>

{#if highlight}
    {filename.slice(0, highlight.start)}<span class="rounded-xs bg-amber-400/50 dark:bg-amber-300/30">{filename.slice(highlight.start, highlight.end)}</span>{filename.slice(highlight.end)}
{:else}
    {filename}
{/if}
