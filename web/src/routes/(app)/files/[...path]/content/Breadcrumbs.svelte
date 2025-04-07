<script lang="ts">
    import ChevronRightIcon from '$lib/component/icons/ChevronRightIcon.svelte';
    import { Popover } from 'bits-ui';
    import { goto } from '$app/navigation';
    import { breadcrumbState, type Segment } from './code/breadcrumbState.svelte';
    import { filesState } from './code/filesState.svelte';

    function openEntry(path: string) {
        goto(`/files${path}`)
    }
</script>

<!-- Breadcrumbs -->
<div class="w-full flex items-center h-[2rem] overflow-hidden">
    {#if filesState.path === "/"}
        <p class="px-2 py-1">Files</p>
    {:else}
        {@const hiddenEmpty = breadcrumbState.hidden.length < 1}
        <!-- Change chevron width in breadcrumb calculator -->

        {#snippet breadcrumbButton(segment: Segment, className: string)}
            <button disabled={filesState.path === segment.path} title={segment.name} on:click={() => { openEntry(`/${segment.path}`) }} class="py-1 px-2 whitespace-nowrap max-w-full truncate {className}">{segment.name}</button>
        {/snippet}

        {#if !hiddenEmpty}
            <div class="size-fit z-10 shrink-0">
                <Popover.Root>
                    <Popover.Trigger>
                        <button class="rounded py-1 px-2 hover:bg-neutral-300 dark:hover:bg-neutral-800">...</button>
                    </Popover.Trigger>
                    <Popover.Content align="start" sideOffset={8}>
                        <div class="min-w-[20rem] w-fit max-w-[min(100vw,40rem)] rounded-lg bg-neutral-300 dark:bg-neutral-800 py-2">
                            {#each breadcrumbState.hidden as segment}
                                {@render breadcrumbButton(segment, "truncate w-full text-start hover:bg-neutral-400 dark:hover:bg-neutral-700")}
                            {/each}
                        </div>
                    </Popover.Content>
                </Popover.Root>
            </div>
        {/if}
        
        <div class="h-full flex items-center">
            {#each breadcrumbState.visible as segment, index}
                <div class="flex items-center h-full ">
                    {#if index !== 0 || !hiddenEmpty}
                        <div class="h-full py-2 flex items-center justify-center shrink-0">
                            <ChevronRightIcon />
                        </div>
                    {/if}
                    {@render breadcrumbButton(segment, "rounded hover:bg-neutral-300 dark:hover:bg-neutral-800")}
                </div>
            {/each}
        </div>
    {/if}
</div>