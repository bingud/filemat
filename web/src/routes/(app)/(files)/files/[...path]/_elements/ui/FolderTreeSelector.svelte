<script lang="ts">
    import { getFileData } from '$lib/code/module/files'
    import { filesState } from '$lib/code/stateObjects/filesState.svelte'
    import { folderSelectorState } from '$lib/code/stateObjects/subState/utilStates.svelte'
    import { explicitEffect } from '$lib/code/util/codeUtil.svelte'
    import { Dialog } from '$lib/component/bits-ui-wrapper'
    import ChevronDownIcon from '$lib/component/icons/ChevronDownIcon.svelte'
    import ChevronRightIcon from '$lib/component/icons/ChevronRightIcon.svelte'
    import { onMount } from 'svelte'

    interface FolderNode {
        name: string
        fullPath: string
        children: FolderNode[] | null
        isSelected: boolean
        isLoading: boolean
        isExpanded: boolean
        isLoaded: boolean
        isHierarchy: boolean
    }

    let dialogTitle = $state('Select a folder')
    let folderTree: FolderNode | null = $state(null)
    let selectedFolderPath: string | null = $state(null)
    let defaultFilename: string | null = $state(null)
    let filenameInput: string = $state("")
    let addressBarValue = $state("")
    let hideFilenameInput = $state(false)

    let debounceTimer: ReturnType<typeof setTimeout> | undefined = undefined

    let selectedPath = $derived.by(() => {
        if (hideFilenameInput) {
            return selectedFolderPath || ""
        }

        if (!selectedFolderPath && !filenameInput) return null
        
        const folder = selectedFolderPath || ""
        const divider = folder === "/" ? "" : "/" 
        return folder + divider + filenameInput.replaceAll("/", "")
    })

    let initialFolder: string | null = $state(null)
    let hasScrolledToInitial: boolean = $state(false)
    let resolvePromise: ((value: string | null) => void) | null = $state(null)
    
    type FilenameProps = { defaultFilename?: undefined, resultType?: undefined } 
        | { defaultFilename: string | null, resultType: "destination" }

    export function show(options: FilenameProps & {
        title?: string,
        initialSelection?: string,
        hideFilenameInput?: boolean
    } = {}) {
        if (options.title) dialogTitle = options.title
        if (options.hideFilenameInput) hideFilenameInput = true
        
        // Parse initialSelection
        if (options.initialSelection) {
            if (hideFilenameInput || options.defaultFilename) {
                // Filename hidden or provided separately - initialSelection is the folder
                initialFolder = options.initialSelection
            } else {
                // No filename provided - parse it from initialSelection
                const lastSlash = options.initialSelection.lastIndexOf(`/`)
                if (lastSlash === 0) {
                    initialFolder = `/`
                    filenameInput = options.initialSelection.substring(1)
                } else if (lastSlash > 0) {
                    initialFolder = options.initialSelection.substring(0, lastSlash)
                    filenameInput = options.initialSelection.substring(lastSlash + 1)
                } else {
                    initialFolder = `/`
                    filenameInput = options.initialSelection
                }
            }
        }
        
        // Set filename from defaultFilename
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
            initialFolder = null
            hasScrolledToInitial = false
            defaultFilename = null
            filenameInput = ""
            addressBarValue = ""
            hideFilenameInput = false
        }
    })

    explicitEffect(() => [selectedPath], () => {
        const inputEl = document.getElementById('address-bar-input')
        if (document.activeElement !== inputEl) {
            addressBarValue = selectedPath || ""
        }
    })

    function handleAddressInput(e: Event) {
        const val = (e.target as HTMLInputElement).value
        addressBarValue = val
        
        if (debounceTimer) clearTimeout(debounceTimer)
        
        debounceTimer = setTimeout(() => {
            syncFromAddressBar(val)
        }, 300)
    }

    function syncFromAddressBar(fullPath: string) {
        let folder = "/"
        let file = ""

        if (hideFilenameInput) {
            folder = fullPath || "/"
            file = ""
        } else {
            const lastSlashIndex = fullPath.lastIndexOf('/')
            
            if (lastSlashIndex !== -1) {
                if (lastSlashIndex === 0) {
                    folder = "/"
                    file = fullPath.substring(1)
                } else {
                    folder = fullPath.substring(0, lastSlashIndex)
                    file = fullPath.substring(lastSlashIndex + 1)
                }
            } else {
                file = fullPath
            }
        }

        selectedFolderPath = folder
        filenameInput = file

        if (folderTree) {
            selectFolderByPath(folder)
        }
    }

    function selectFolderByPath(path: string) {
        if (!folderTree) return
        deselectAllNodes(folderTree)

        if (path === "/" || path === "") {
            folderTree.isSelected = true
            return
        }

        const segments = path.split('/').filter(s => s.length > 0)
        let currentNode = folderTree

        for (const segment of segments) {
            if (!currentNode.children) return
            const next = currentNode.children.find(c => c.name === segment)
            if (!next) return
            currentNode = next
        }

        currentNode.isSelected = true
    }

    function getParentPath(path: string): string {
        const segments = path.split(`/`).filter(s => s.length > 0)
        const parentSegments = segments.slice(0, -1)
        return parentSegments.length === 0 ? `/` : `/` + parentSegments.join(`/`)
    }

    async function initializeTree() {
        folderTree = {
            name: "",
            fullPath: "/",
            children: null,
            isSelected: false,
            isLoading: false,
            isExpanded: false,
            isLoaded: false,
            isHierarchy: true
        }

        // Determine folder and parent to expand
        // If no initialFolder, default to root
        const folder = initialFolder || `/`
        const parent = getParentPath(folder)
        
        // Build skeleton and select the folder
        buildSkeleton(folder)
        
        // Expand folder and parent
        const folderNode = findNodeByPath(folder)
        const parentNode = findNodeByPath(parent)
        
        if (folderNode) folderNode.isExpanded = true
        if (parentNode) parentNode.isExpanded = true
        
        // Load data for folder and parent in parallel
        await Promise.all([
            loadPathData(folder),
            loadPathData(parent)
        ])
    }

    function findNodeByPath(path: string): FolderNode | null {
        if (!folderTree) return null
        if (path === "/") return folderTree

        const segments = path.split('/').filter(s => s.length > 0)
        let currentNode = folderTree

        for (const segment of segments) {
            if (!currentNode.children) return null
            const next = currentNode.children.find(c => c.name === segment)
            if (!next) return null
            currentNode = next
        }
        return currentNode
    }

    function buildSkeleton(path: string) {
        if (!folderTree) return
        
        const segments = path.split('/').filter(s => s.length > 0)
        let currentNode = folderTree
        
        if (path === "/") {
            selectFolder(currentNode)
            return
        }

        for (const segment of segments) {
            if (currentNode.children === null) {
                currentNode.children = []
            }

            let child = currentNode.children.find(c => c.name === segment)
            
            if (!child) {
                const parentPathPrefix = currentNode.fullPath === "/" ? "" : currentNode.fullPath
                child = {
                    name: segment,
                    fullPath: `${parentPathPrefix}/${segment}`,
                    children: null,
                    isSelected: false,
                    isLoading: false,
                    isExpanded: false,
                    isLoaded: false,
                    isHierarchy: true
                }
                currentNode.children.push(child)
            } else {
                child.isHierarchy = true
            }
            
            currentNode = child
        }

        selectFolder(currentNode)
    }

    async function loadPathData(path: string) {
        const node = findNodeByPath(path)
        if (!node) return

        node.isLoading = true
        const dataResult = await getFileData(path, filesState.meta.fileEntriesUrlPath, undefined, { foldersOnly: true })
        node.isLoading = false
        node.isLoaded = true 
        
        const data = dataResult.value
        if (dataResult.isUnsuccessful || !data?.entries) return

        const apiChildren = data.entries.map((entry) => ({
            name: entry.filename!,
            fullPath: entry.path,
            children: null,
            isSelected: false,
            isLoading: false,
            isExpanded: false,
            isLoaded: false,
            isHierarchy: false
        }))

        const finalChildren: FolderNode[] = []

        if (node.children) {
            node.children.forEach(existing => {
                const matchIndex = apiChildren.findIndex(api => api.name === existing.name)
                
                if (matchIndex !== -1) {
                    apiChildren.splice(matchIndex, 1)
                    finalChildren.push(existing)
                } else {
                    finalChildren.push(existing)
                }
            })
        }

        finalChildren.push(...apiChildren)
        finalChildren.sort((a, b) => a.name.localeCompare(b.name))

        node.children = finalChildren
    }

    async function toggleFolder(node: FolderNode) {
        if (!node.isLoaded) {
            await loadPathData(node.fullPath)
            
            if (selectedFolderPath) {
                selectFolderByPath(selectedFolderPath)
            }
        }
        node.isExpanded = !node.isExpanded
    }

    function selectFolder(node: FolderNode) {
        if (folderTree) {
            deselectAllNodes(folderTree)
        }
        node.isSelected = true
        selectedFolderPath = node.fullPath
    }

    function deselectAllNodes(node: FolderNode) {
        node.isSelected = false
        if (node.children) {
            node.children.forEach(child => deselectAllNodes(child))
        }
    }

    function confirmSelection() {
        folderSelectorState.isOpen = false
        if (resolvePromise) {
            resolvePromise(selectedPath)
        }
    }

    function onSelectedFolderElementMount(e: HTMLElement) {
        hasScrolledToInitial = true
        e.scrollIntoView({ block: "center" })
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
                <div class="flex items-center gap-1 bg-neutral-100 dark:bg-neutral-800 border-b border-neutral-200 dark:border-neutral-700 h-10 px-3">
                    <span class="mr-1 opacity-50 text-sm whitespace-nowrap">Selected:</span>
                    <input 
                        id="address-bar-input"
                        type="text" 
                        class="w-full bg-transparent !border-none !outline-none !ring-0 font-medium text-sm text-neutral-900 dark:text-neutral-100 placeholder-neutral-400"
                        value={addressBarValue}
                        on:input={handleAddressInput}
                        spellcheck="false"
                        autocomplete="off"
                    />
                </div>
                
                <div class="flex-1 p-1 overflow-y-auto custom-scrollbar min-h-0">
                    {#if folderTree}
                        {@render treeNode(folderTree)}
                    {:else}
                        <div class="flex items-center justify-center h-full text-neutral-500">
                            <span>Loading...</span>
                        </div>
                    {/if}
                </div>  
            </div>

            {#if !hideFilenameInput}
                <div class="flex gap-4 items-center">
                    <label for="selected-filename-input" class="!w-fit">Filename:</label>
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
    <div class="w-full relative">
        {#if node.isSelected && !hasScrolledToInitial}
            <div use:onSelectedFolderElementMount class="size-0 absolute"></div>
        {/if}

        <div class="flex items-center py-1 {node.isSelected ? 'bg-blue-100 dark:bg-blue-900' : 'hover:bg-neutral-100 dark:hover:bg-neutral-700'} rounded cursor-pointer"
            style="padding-left: {level}rem"
            on:click={() => selectFolder(node)}
            on:dblclick={(e) => { e.stopPropagation(); toggleFolder(node); }}
        >
            <button class="mr-1 text-neutral-500 cursor-pointer aspect-square h-[1.5rem]" on:click|stopPropagation={() => toggleFolder(node)}>
                <div class="h-[0.8rem] my-auto">
                    {#if (node.children && node.children.length > 0) || !node.isLoaded}
                        {#if node.isExpanded}
                            <ChevronDownIcon />
                        {:else}
                            <ChevronRightIcon />
                        {/if}
                    {:else}
                        <div class="w-4"></div>
                    {/if}
                </div>
            </button>

            <span class="mr-2 {node.fullPath === initialFolder ? 'font-bold text-blue-600 dark:text-blue-400' : ''}">
                {node.name === "" ? "Root" : node.name}
                {#if node.fullPath === initialFolder}
                    <span class="text-[10px] ml-1 opacity-70">(Current Folder)</span>
                {/if}
            </span>
            
            {#if node.isLoading}
                <span class="text-neutral-400">⟳</span>
            {/if}
        </div>

        {#if node.children && node.children.length > 0}
            <div class="w-full">
                {#each node.children as child}
                    {#if node.isExpanded || child.isHierarchy}
                        {@render treeNode(child, level + 1)}
                    {/if}
                {/each}
            </div>
        {/if}
    </div>
{/snippet}