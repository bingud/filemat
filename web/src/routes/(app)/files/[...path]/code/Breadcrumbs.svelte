<script lang="ts">
    import ChevronRightIcon from '$lib/component/icons/ChevronRightIcon.svelte';
    import { Popover } from 'bits-ui';
    import { filesState } from './filesState.svelte';
    import { breadcrumbState, type Segment } from './breadcrumbState.svelte';



</script>

<!-- Breadcrumbs -->
<div bind:offsetWidth={breadcrumbState.containerWidth} class="flex items-center h-[2rem] w-[85%]">
    {#if filesState.path === "/"}
        <p class="px-2 py-1">Files</p>
    {:else}
        {@const hiddenEmpty = breadcrumbState.hidden.length < 1}
        <!-- Change chevron width in breadcrumb calculator -->

        {#snippet breadcrumbButton(segment: Segment, className: string)}
            <button disabled={filesState.path === segment.path} title={segment.name} on:click={() => { opener(`/${segment.path}`) }} class="py-1 px-2 {className}">{segment.name}</button>
        {/snippet}

        {#if !hiddenEmpty}
            <Popover.Root>
                <Popover.Trigger>
                    <button class="rounded py-1 px-2 hover:bg-neutral-300 dark:hover:bg-neutral-800">...</button>
                </Popover.Trigger>
                <Popover.Content align="start" sideOffset={8}>
                    <div class="w-[min(20rem,fit-content)] max-w-screen rounded-lg bg-neutral-800 py-2">
                        {#each breadcrumbState.hidden as segment}
                            {@render breadcrumbButton(segment, "truncate w-full text-start hover:bg-neutral-700")}
                        {/each}
                    </div>
                </Popover.Content>
            </Popover.Root>
        {/if}
        
        {#each breadcrumbState.visible as segment, index}
            <div class="flex items-center h-full">
                {#if index !== 0 || !hiddenEmpty}
                    <div class="h-full py-2 flex items-center justify-center">
                        <ChevronRightIcon />
                    </div>
                {/if}
                {@render breadcrumbButton(segment, "rounded hover:bg-neutral-300 dark:hover:bg-neutral-800")}
            </div>
        {/each}
    {/if}
</div>