<script lang="ts">
    import ChevronRightIcon from '$lib/component/icons/ChevronRightIcon.svelte'
    import { Popover } from 'bits-ui'
    import { goto } from '$app/navigation'
    import { filesState } from '$lib/code/stateObjects/filesState.svelte'
    import InfoIcon from '$lib/component/icons/InfoIcon.svelte'
    import { calculateTextWidth } from "$lib/code/util/uiUtil"
    import { breadcrumbState, type Segment } from '../../_code/breadcrumbState.svelte'
    import { openEntry } from '../../_code/fileBrowserUtil';
    import CheckmarkIcon from '$lib/component/icons/CheckmarkIcon.svelte';
    import CloseIcon from '$lib/component/icons/CloseIcon.svelte';

    const meta = $derived(filesState.meta)

    // Context menu for breadcrumb buttons
    let contextMenuButton: HTMLButtonElement | null = $state(null)
    let menuSegment: Segment | null = $state(null)
    let contextMenuOpen = $state(false)

    let topLevelButtonText = $derived.by(() => {
        if (meta.type === "shared") {
            const filename = meta.shareTopLevelFilename
            if (filename) return filename
        }

        return meta.pageTitle
    })

    function onContextMenu(event: MouseEvent, segment: Segment, button: HTMLButtonElement) {
        if (filesState.data.currentMeta == null) return
        event.preventDefault()
        contextMenuButton = button
        menuSegment = segment
        contextMenuOpen = true
    }

    function onContextMenuOpenChange(open: boolean) {
        if (!open) {
            contextMenuButton = null
            menuSegment = null
        }
    }

    function option_details(segment: Segment) {
        filesState.selectedEntries.setSelected(segment.path === "" ? `/` : `/${segment.path}`)
        filesState.ui.detailsOpen = true
        closeContextMenu()
    }

    function option_selectAllFiles() {
        if (!filesState.data.entries) return
        filesState.selectedEntries.list = filesState.data.entries.map(v => v.path)
        closeContextMenu()
    }

    function option_unselectAllFiles() {
        filesState.selectedEntries.list = []
        closeContextMenu()
    }

    function closeContextMenu() {
        contextMenuButton = null
        menuSegment = null
    }
</script>


<!-- Breadcrumbs -->
<div class="w-full flex items-center h-[2rem] max-h-full overflow-hidden">
    {#if filesState.path === "/" || meta.type === "accessible"}
        <button 
            title={topLevelButtonText}
            on:click={() => {
                if (meta.type === "accessible") {
                    openEntry(`/`)
                }
            }}
            on:contextmenu={(e) => { onContextMenu(e, { name: topLevelButtonText, path: "", width: calculateTextWidth(topLevelButtonText) }, e.currentTarget) }}
            class="py-1 px-2 whitespace-nowrap max-w-full truncate rounded hover:bg-neutral-300 dark:hover:bg-neutral-800"
        >{topLevelButtonText}</button>
    {:else}
        {@const hiddenEmpty = breadcrumbState.hidden.length < 1}
        <!-- Change chevron width in breadcrumb calculator -->

        {#snippet breadcrumbButton(
            segment: Segment, 
            o: { classes: string, withPopup: Boolean, isClickable: boolean })}
            <button 
                disabled={filesState.path === segment.path} 
                title={segment.name} 
                on:click={() => {
                    if (o.isClickable) { 
                        filesState.search.clear()
                        openEntry(`/${segment.path}`)
                    }
                }} 
                on:contextmenu={(e) => { if (o.withPopup) { onContextMenu(e, segment, e.currentTarget) } }}
                class="py-1 px-2 whitespace-nowrap max-w-full truncate {o.classes}"
            >{segment.name}</button>
        {/snippet}

        {#if !hiddenEmpty}
            <div class="size-fit z-[1] shrink-0">
                <Popover.Root>
                    <Popover.Trigger>
                        <button class="rounded py-1 px-2 hover:bg-neutral-300 dark:hover:bg-neutral-800">...</button>
                    </Popover.Trigger>
                    <Popover.Content align="start" sideOffset={8}>
                        <div class="min-w-[20rem] w-fit max-w-[min(100vw,40rem)] rounded-lg bg-neutral-300 dark:bg-neutral-800 py-2">
                            {#each breadcrumbState.hidden as segment}
                                {@render breadcrumbButton(
                                    segment, {
                                        classes: "truncate w-full text-start hover:bg-neutral-400 dark:hover:bg-neutral-700", 
                                        withPopup: true,
                                        isClickable: true
                                    }
                                )}
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
                        <div class="h-full flex items-center justify-center shrink-0">
                            <ChevronRightIcon class="h-[0.6rem]" />
                        </div>
                    {/if}
                    {@render breadcrumbButton(
                        segment, {
                            classes: "rounded hover:bg-neutral-300 dark:hover:bg-neutral-800", 
                            withPopup: (breadcrumbState.visible.length - 1 === index),
                            isClickable: index !== breadcrumbState.fullList.length - 1
                        }
                    )}
                </div>
            {/each}
        </div>
    {/if}
</div>

<!-- Context menu for breadcrumb buttons -->
{#if contextMenuButton && menuSegment && !filesState.isSearchOpen}
    {#key contextMenuButton || menuSegment}
        <div class="z-50 relative">
            <Popover.Root bind:open={contextMenuOpen} onOpenChange={onContextMenuOpenChange}>
                <Popover.Content onInteractOutside={() => { contextMenuOpen = false }} customAnchor={contextMenuButton} align="start" >
                    <div class="w-[14rem] max-w-full max-h-full rounded-lg bg-neutral-250 dark:bg-neutral-800 py-2 flex flex-col z-50">
                        <button on:click={() => { option_details(menuSegment!) }} class="py-1 px-4 text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700 flex items-center gap-2">
                            <div class="size-5 flex-shrink-0">
                                <InfoIcon />
                            </div>
                            <span>Details</span>
                        </button>
                    </div>
                </Popover.Content>
            </Popover.Root>
        </div>
    {/key}
{/if}