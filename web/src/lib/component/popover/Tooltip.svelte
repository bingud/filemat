<script lang="ts">
    import type { snippet } from "$lib/code/types/types"
    import { Tooltip } from "bits-ui"

    let {
        children,
        text,
        delay = 400,
        class: classes,
        side = "top",
        align = "center",
    }: {
        children: snippet,
        text: string,
        delay?: number,
        class?: string,
        side?: "top" | "bottom",
        align?: "start" | "center" | "end"
    } = $props()
</script>


<Tooltip.Root delayDuration={delay}>
    <Tooltip.Trigger>
        {#snippet child({props})}
            <span {...props} class={classes}>
                {@render children()}
            </span>
        {/snippet}
    </Tooltip.Trigger>
    <Tooltip.Portal>
        <Tooltip.Content {side} {align} class="surface-popover-container px-3 !max-w-screen overflow-hidden !break-words">
            {text}
        </Tooltip.Content>
    </Tooltip.Portal>
</Tooltip.Root>