<script lang="ts">
    import type { snippet } from '$lib/code/types/types';
    import { Dialog } from '$lib/component/bits-ui-wrapper'

    let {
        children,
        title,
        onOpenChange,
        isOpen = $bindable(),
        classes,
    }: {
        children: snippet
        title?: string,
        onOpenChange?: (state: boolean) => any,
        isOpen: boolean,
        classes?: string
    } = $props()
</script>


<Dialog.Root bind:open={isOpen} onOpenChange={onOpenChange}>
    <Dialog.Content
        class="
            {classes || ""}
            fixed left-[50%] top-[50%] z-50 box-border
            grid min-size-0 max-size-sv gap-4 overflow-hidden
            translate-x-[-50%] translate-y-[-50%]
            p-6 shadow-md duration-200
            border-[1px] border-neutral-300 bg-neutral-50
            data-[state=open]:animate-in
            data-[state=closed]:animate-out
            data-[state=closed]:fade-out-0
            data-[state=open]:fade-in-0
            data-[state=closed]:zoom-out-95
            data-[state=open]:zoom-in-95
            data-[state=closed]:slide-out-to-left-1/2
            data-[state=closed]:slide-out-to-top-[48%]
            data-[state=open]:slide-in-from-left-1/2
            data-[state=open]:slide-in-from-top-[48%]
            sm:rounded-sm
            dark:border-neutral-700 dark:bg-neutral-800
        "
    >
        {#if title}
            <Dialog.Title class="text-lg font-semibold text-neutral-800 dark:text-neutral-50">
                {title}
            </Dialog.Title>
        {/if}
        
        {@render children()}
    </Dialog.Content>
</Dialog.Root>