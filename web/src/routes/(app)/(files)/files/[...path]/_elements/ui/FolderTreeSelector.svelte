<script lang="ts">
    import { getFileData } from '$lib/code/module/files';
    import { filesState } from '$lib/code/stateObjects/filesState.svelte';
    import { folderSelectorState } from '$lib/code/stateObjects/subState/utilStates.svelte';
    import { explicitEffect } from '$lib/code/util/codeUtil.svelte';
    import { Dialog } from '$lib/component/bits-ui-wrapper'
    import ChevronDownIcon from '$lib/component/icons/ChevronDownIcon.svelte';
    import ChevronRightIcon from '$lib/component/icons/ChevronRightIcon.svelte';
    import { onMount, untrack } from 'svelte';

    interface FolderNode {
        name: string
        fullPath: string
        children: FolderNode[] | null
        isSelected: boolean
        isLoading: boolean,
        isExpanded: boolean,
    }

    // Component state
    let dialogTitle = $state('Select a folder')
    let folderTree: FolderNode | null = $state(null)
    let selectedFolderPath: string | null = $state(null)
    let isFilenameChangable: boolean | null = $state(null)
    let defaultFilename: string | null = $state(null)

    let filenameInput: string = $state("")

    let selectedPath = $derived.by(() => {
        if (!isFilenameChangable) {
            return selectedFolderPath
        }

        if (!filenameInput || !selectedFolderPath) return null
        
        const divider = selectedFolderPath === "/" ? "" : "/"
        return selectedFolderPath + divider + filenameInput.replaceAll("/", "")
    })

    let initialSelection: string | null = $state(null)
    let hasScrolledToInitial: boolean = $state(false)
    
    // Promise resolver functions
    let resolvePromise: ((value: string | null) => void) | null = $state(null)
    
    type FilenameProps = { isFilenameChangable?: undefined, defaultFilename?: undefined, resultType?: undefined } 
        | { isFilenameChangable: boolean, defaultFilename: string | null, resultType: "destination" }

    // Public API - returns a Promise that resolves to a selected folder path, or null.
    export function show(options: FilenameProps & {
        title?: string,
        initialSelection?: string,
    } = {}) {
        if (options.title) dialogTitle = options.title
        if (options.initialSelection) initialSelection = options.initialSelection
        if (options.isFilenameChangable) isFilenameChangable = options.isFilenameChangable
        if (options.defaultFilename) {
            defaultFilename = options.defaultFilename
            filenameInput = defaultFilename
        }

        folderSelectorState.isOpen = true
        
        return new Promise<string | null>((resolve) => {
            resolvePromise = resolve
        })
    }

    onMount(() => {
        folderSelectorState.show = show
    })

    function handleClose() {
        if (folderSelectorState.isOpen) {
            if (resolvePromise) resolvePromise(null)
            resolvePromise = null
            folderSelectorState.isOpen = false
        }
    }

    explicitEffect(() => [ 
        folderSelectorState.isOpen 
    ], () => {
        if (folderSelectorState.isOpen === true) {
            initializeTree()
        } else {
            if (resolvePromise) {
                resolvePromise(null)
                resolvePromise = null
            }

            folderTree = null
            initialSelection = null
            hasScrolledToInitial = false
            isFilenameChangable = null
            defaultFilename = null

            filenameInput = ""
        }
    })

    async function initializeTree() {
        folderTree = {
            name: "",
            fullPath: "/",
            children: null,
            isSelected: false,
            isLoading: true,
            isExpanded: true,
        }

        // Load the children of root folder
        await loadPathData("/")

        if (initialSelection) {
            await expandToPath(initialSelection)
            initialSelection = null
        }
    }

    async function loadPathData(parent: string) {
        const segments = parent.split('/').filter(s => s.length > 0)
        let node: FolderNode | null = folderTree!
        for (const segment of segments) {
            if (!node || !node.children) break
            node = node.children.find(v => v.name === segment) || null
        }
        if (!node) return

        node.isLoading = true
        const dataResult = await getFileData(parent, filesState.meta.fileEntriesUrlPath, undefined, { foldersOnly: true })
        node.isLoading = false
        
        const data = dataResult.value
        if (dataResult.isUnsuccessful || !data?.entries) return

        const nodeChildren: FolderNode[] = data.entries.map((child) => {
            return {
                name: child.filename!,
                fullPath: child.path,
                children: null,
                isSelected: false,
                isLoading: false,
                isExpanded: false,
            }
        })
        node.children = nodeChildren
    }

    // Toggle folder expansion
    async function toggleFolder(node: FolderNode) {
        if (!node.children) {
            await loadPathData(node.fullPath)
        }
        node.isExpanded = !node.isExpanded
    }

    // Select a folder
    function selectFolder(node: FolderNode) {
        // Deselect previously selected folder if any
        if (folderTree) {
            deselectAllNodes(folderTree)
        }
        
        // Select the new folder
        node.isSelected = true
        selectedFolderPath = node.fullPath
    }

    // Recursive function to deselect all nodes
    function deselectAllNodes(node: FolderNode) {
        node.isSelected = false
        if (node.children) {
            node.children.forEach(child => deselectAllNodes(child))
        }
    }

    // Confirm selection and resolve promise
    function confirmSelection() {
        folderSelectorState.isOpen = false
        if (!resolvePromise) return
        
        
        resolvePromise(selectedPath)
    }

    /** 
     * Recursively loads & expands each segment, then selects the final node 
     */
    async function expandToPath(path: string) {
        const segments = path.split('/').filter(s => s.length > 0)
        let node: FolderNode | null = folderTree

        for (const segment of segments) {
            if (!node) return

            if (node.children === null) {
                await loadPathData(node.fullPath)
            }

            const child = node.children?.find(c => c.name === segment) || null
            if (!child) return

            child.isExpanded = true
            node = child
        }

        if (node) selectFolder(node)
    }

    function onSelectedFolderElementMount(e: HTMLElement) {
        hasScrolledToInitial = true
        e.scrollIntoView()
    }
</script>

<Dialog.Root bind:open={folderSelectorState.isOpen} onOpenChange={handleClose}>
    <Dialog.Content 
        class="fixed left-[50%] top-[50%] z-50 flex flex-col w-[40rem] max-w-full h-[40rem] max-h-full translate-x-[-50%] translate-y-[-50%] 
            border-[1px] border-neutral-300 dark:border-neutral-700
            bg-neutral-50 dark:bg-neutral-800
            gap-4 p-6 sm:rounded-sm shadow-md duration-200 !select-none
            data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 
            data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95 data-[state=closed]:slide-out-to-left-1/2 data-[state=closed]:slide-out-to-top-[48%] 
            data-[state=open]:slide-in-from-left-1/2 data-[state=open]:slide-in-from-top-[48%]
        "
    >
        <Dialog.Title class="text-lg font-semibold text-neutral-800 dark:text-neutral-50 h-fit">
            {dialogTitle}
        </Dialog.Title>

        <div class="flex flex-col flex-1 w-full min-h-0 gap-4">
            <div class="flex flex-col flex-1 min-h-0 border border-neutral-200 dark:border-neutral-700 rounded bg-white dark:bg-neutral-900 overflow-hidden">
                <div class="p-2 bg-neutral-100 dark:bg-neutral-800 border-b border-neutral-200 dark:border-neutral-700">
                    <div class="flex items-center text-sm gap-1">
                        <span class="mr-1 opacity-50">Selected:</span>
                        <span class="font-medium">{selectedPath || "-"}</span>
                    </div>
                </div>
                
                <div class="flex-1 p-1 overflow-y-auto min-h-0">
                    {#if folderTree}
                        {@render treeNode(folderTree)}
                    {:else}
                        <div class="flex items-center justify-center h-full text-neutral-500">
                            <span>Loading...</span>
                        </div>
                    {/if}
                </div>  
            </div>

            {#if isFilenameChangable}
                <div class="flex gap-4 items-center">
                    <label for="selected-filename-input !w-fit">Filename:</label>
                    <input id="selected-filename-input" bind:value={filenameInput} class="basic-input !max-w-full !flex-grow">
                </div>
            {/if}
            
            <div class="flex justify-end gap-2 mt-4">
                <button 
                    class="px-4 py-2 rounded bg-neutral-200 hover:bg-neutral-300 text-neutral-800 dark:bg-neutral-700 dark:hover:bg-neutral-600 dark:text-neutral-100"
                    on:click={handleClose}
                >
                    Cancel
                </button>
                <button 
                    class="px-4 py-2 rounded bg-blue-500 hover:bg-blue-600 text-white disabled:opacity-50 disabled:cursor-not-allowed"
                    on:click={confirmSelection}
                    disabled={!selectedPath}
                >
                    Select
                </button>
            </div>
        </div>
    </Dialog.Content>

</Dialog.Root>


{#snippet treeNode(node: FolderNode, level: number = 0)}
    <div  class="w-full relative">
        {#if node.isSelected && !hasScrolledToInitial}
            <div use:onSelectedFolderElementMount class="size-0 absolute"></div>
        {/if}

        <div class="flex items-center py-1 ${node.isSelected ? 'bg-blue-100 dark:bg-blue-900' : 'hover:bg-neutral-100 dark:hover:bg-neutral-700'} rounded cursor-pointer"
            style="padding-left: {level}rem"
            on:click={() => selectFolder(node)}
        >
            <button class="mr-1 text-neutral-500 cursor-pointer aspect-square h-[1.5rem]" on:click|stopPropagation={() => toggleFolder(node)}>
                <div class="h-[0.8rem] my-auto">
                    {#if node.children !== null}
                        {#if node.isExpanded}
                            <ChevronDownIcon />
                        {:else}
                            <ChevronRightIcon />
                        {/if}
                    {:else}
                        <ChevronRightIcon />
                    {/if}
                </div>
            </button>

            <span class="mr-2">
                {#if node.name === ""}
                    Root
                {:else}
                    {node.name}
                {/if}
            </span>
            {#if node.isLoading}
                <span class="text-neutral-400">⟳</span>
            {/if}
        </div>

        {#if node.isExpanded && node.children && node.children.length > 0}
            <div class="w-full">
                {#each node.children as child}
                    {@render treeNode(child, level + 1)}
                {/each}
            </div>
        {/if}
    </div>
{/snippet}