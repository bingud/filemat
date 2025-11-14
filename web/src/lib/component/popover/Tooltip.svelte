<script lang="ts">
    import type { snippet } from "$lib/code/types/types";
    import { Tooltip } from "bits-ui";

    let {
        children,
        text
    }: {
        children: snippet,
        text: string,
    } = $props()
</script>


<Tooltip.Root>
    <Tooltip.Trigger>
        {#snippet child({props})}
            <span {...props} class="">
                {@render children()}
            </span>
        {/snippet}
    </Tooltip.Trigger>
    <Tooltip.Portal>
        <Tooltip.Content class="relative z-50 surface-popover-container px-3">
            {text}
        </Tooltip.Content>
    </Tooltip.Portal>
</Tooltip.Root>