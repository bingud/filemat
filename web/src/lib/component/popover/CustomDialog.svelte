<script lang="ts">
    import type { snippet } from '$lib/code/types/types';
    import { Dialog } from '$lib/component/bits-ui-wrapper'

    let {
        children,
        title,
        description,
        onOpenChange,
        isOpen = $bindable(),
        classes,
    }: {
        children: snippet,
        title?: string,
        description?: string,
        onOpenChange?: (state: boolean) => any,
        isOpen: boolean,
        classes?: string
    } = $props()
</script>


<Dialog.Root bind:open={isOpen} onOpenChange={onOpenChange}>
    <Dialog.Portal>
        <Dialog.Overlay
            class="fixed inset-0 z-50 bg-black/50"
        />
        <Dialog.Content
            class="
                {classes || ""} fixed left-[50%] top-[50%] z-50 grid w-full max-w-md md:w-full translate-x-[-50%] translate-y-[-50%] gap-4 p-6
                bg-surface sm:rounded-sm
            "
        >
            {#if title}
                <Dialog.Title class="text-lg font-semibold text-neutral-800 dark:text-neutral-50">
                    {title}
                </Dialog.Title>
            {/if}
            {#if description}
                <Dialog.Description class="text-sm">
                    {description}
                </Dialog.Description>
            {/if}
            
            {@render children()}
        </Dialog.Content>
    </Dialog.Portal>
</Dialog.Root>