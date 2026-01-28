<script lang="ts">
    import type { Snippet } from "svelte"
    import { Tooltip } from "bits-ui"

    let {
        children,
        text,
        delay = 400,
        class: classes,
        side = "top",
        align = "center",
    }: {
        children: Snippet
        text: string
        delay?: number
        class?: string
        side?: "top" | "bottom"
        align?: "start" | "center" | "end"
    } = $props()

    let open = $state(false)
</script>

<Tooltip.Root delayDuration={delay} bind:open>
    <Tooltip.Trigger onclick={() => (open = !open)}>
        {#snippet child({ props })}
            <span {...props} class={classes}>
                {@render children()}
            </span>
        {/snippet}
    </Tooltip.Trigger>
    <Tooltip.Portal>
        <Tooltip.Content
            {side}
            {align}
            class="surface-popover-container px-3 !max-w-screen overflow-hidden !break-words"
        >
            {text}
        </Tooltip.Content>
    </Tooltip.Portal>
</Tooltip.Root>