<script lang="ts">
    import type { FileMetadata, FullFileMetadata } from "$lib/code/auth/types"
    import { filenameFromPath, parentFromPath, appendFilename, resolvePath, isFolder, safeFetch, formData, handleException, handleErr } from "$lib/code/util/codeUtil.svelte"
    import { onMount } from "svelte"
    import { filesState } from "$lib/code/stateObjects/filesState.svelte"
    import UploadPanel from "../ui/UploadPanel.svelte"
    import { uploadState } from "$lib/code/stateObjects/subState/uploadState.svelte"
    import { copyFile, deleteFiles, moveFile, moveMultipleFiles } from "$lib/code/module/files"
    import { confirmDialogState, folderSelectorState, inputDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte"
    import FolderTreeSelector from "../ui/FolderTreeSelector.svelte"
    import { appState } from '$lib/code/stateObjects/appState.svelte'
    import { isDialogOpen } from "$lib/code/util/stateUtils"
    import { textFileViewerState } from "../../_code/textFileViewerState.svelte"
    import { openEntry, selectSiblingFile, scrollSelectedEntryIntoView } from "../../_code/fileBrowserUtil";
    import FileList from "./FileList.svelte";
    import Loader from "$lib/component/Loader.svelte";

    // TODO - Temporary fix
    // Svelte doesnt feel like being reactive
    $effect(() => {
        filesState.currentFile.originalFileCategory
        filesState.currentFile.displayedFileCategory
    })

    onMount(() => {
        // Set the selected entry path
        setSelectedEntryPath()

        window.addEventListener('keydown', handleKeyDown)
        
        return () => {
            // Clean up listener when component unmounts
            window.removeEventListener('keydown', handleKeyDown)
            filesState.ui.filePreviewLoader.destroy()
        }
    })


    function setSelectedEntryPath() {
        const selectedEntryPath = filesState.selectedEntries.selectedPositions.getChild(filesState.path)
        if (!selectedEntryPath) return

        const filename = filesState.path === "/" ? `${selectedEntryPath}` : `${filesState.path}/${selectedEntryPath}`
        
        if (filesState.data.entries?.some(v => v.path === filename)) {
            filesState.selectedEntries.setSelected(filename)
            // Scroll to the selected entry after a small delay to ensure DOM is updated
            scrollSelectedEntryIntoView()
        }
    }

    function handleKeyDown(event: KeyboardEvent) {
        if (isDialogOpen()) return
        if (textFileViewerState.isFocused) return
        if (filesState.metaLoading) return

        // Check if Delete key was pressed
        if (event.key === 'Delete' && !event.ctrlKey && !event.altKey && !event.metaKey) {
            // Check if we have a selected entry
            if (filesState.selectedEntries.singlePath && filesState.data.entries) {
                // Find the selected entry in the entries list
                const selectedEntry = filesState.data.entries.find(e => e.path === filesState.selectedEntries.singlePath)
                if (selectedEntry) {
                    // Delete the selected entry
                    option_delete(selectedEntry)
                }
            }
        } 

        if (event.key === "Enter") {
            if (filesState.selectedEntries.singleMeta) {
                entryOnClick(event, filesState.selectedEntries.singleMeta)
            }
        }
        
        // Handle Up and Down arrow keys for entry navigation
        if (
            (event.key === 'ArrowUp' || event.key === 'ArrowDown' || event.key === 'ArrowLeft' || event.key === 'ArrowRight')
            && !event.ctrlKey && !event.altKey && !event.metaKey 
            && filesState.data.sortedEntries?.length
        ) {
            event.preventDefault()
            
            const direction = event.key === 'ArrowUp' || event.key === 'ArrowLeft'
                ? "previous"
                : "next"
            
            if (filesState.data.fileMeta) {
                selectSiblingFile(direction, true, true)
            } else {
                selectSiblingFile(direction)
            }
        }
    }

    /**
     * onClick for file entry
     */
    function entryOnClick(e: UIEvent, entry: FileMetadata) {
        if (filesState.selectedEntries.singlePath !== entry.path && !appState.settings.clickToOpenFile) {
            e.preventDefault()
            filesState.selectedEntries.setSelected(entry.path)
            // Scroll selected entry into view
            scrollSelectedEntryIntoView()
        } else {
            if (isFolder(entry) && !entry.isExecutable) return
            filesState.selectedEntries.setSelected(entry.path)
            openEntry(entry.path)
        }
    }

    function option_details(entry: FileMetadata) {
        filesState.selectedEntries.setSelected(entry.path)
        filesState.ui.detailsOpen = true
        closeFileContextMenu()
    }

    function option_delete(entry: FileMetadata) {
        // Show confirmation dialog and handle the result
        confirmDialogState.show({
            title: "Delete File",
            message: `Are you sure you want to delete "${entry.filename!}"? This cannot be undone.`,
            confirmText: "Delete",
            cancelText: "Cancel"
        })?.then((confirmed: boolean) => {
            if (confirmed) {
                deleteFiles([entry])
            }
        })
        
        closeFileContextMenu()
    }

    async function option_move(entry: FileMetadata) {
        if (filesState.isSearchOpen) return

        const newParentPath = await folderSelectorState.show!({
            title: "Choose the new location:",
            initialSelection: parentFromPath(entry.path)
        })
        if (!newParentPath) return

        const newPath = appendFilename(newParentPath, entry.filename!)
        moveFile(entry.path, newPath)

        closeFileContextMenu()
    }

    async function option_copy(entry: FileMetadata) {
        const newPath = await folderSelectorState.show!({
            title: "Choose the new location:",
            initialSelection: parentFromPath(entry.path),
            isFilenameChangable: true,
            defaultFilename: entry.filename,
            resultType: "destination"
        })
        if (!newPath) return

        copyFile(entry.path, newPath)

        closeFileContextMenu()
    }

    async function option_save(entry: FileMetadata, action: "save" | "unsave") {
        const isSave = action === "save"

        const path = isSave ? `create` : `remove`
        const response = await safeFetch(`/api/v1/saved-file/${path}`, {
            body: formData({ path: entry.path })
        })
        if (response.failed) {
            handleException(`Failed to ${action} file.`, `Failed to ${action} file.`, response.exception)
            return
        }

        const json = response.json()
        if (response.code.failed) {
            handleErr({
                description: `Failed to ${action} file`,
                notification: json.message || `Failed to ${action} file.`,
                isServerDown: response.code.serverDown
            })
            return
        }

        if (filesState.isSearchOpen && filesState.search.entries) {
            const listEntry = filesState.search.entries.find(v => v.path === entry.path)
            if (listEntry) listEntry.isSaved = isSave
        }
        
        if (filesState.data.entries) {
            const listEntry = filesState.data.entries.find(v => v.path === entry.path)

            if (!isSave && filesState.meta.type === "saved" && listEntry) {
                const index = filesState.data.entries.indexOf(listEntry)

                if (index !== -1) {
                    filesState.data.entries.splice(index, 1)
                }
            } else {
                if (listEntry) listEntry.isSaved = isSave
            }
        }
        
        closeFileContextMenu()
    }

    async function option_rename(entry: FileMetadata) {
        filesState.ui.fileContextMenuPopoverOpen = false
        const newFilename = await inputDialogState.show({
            title: "Rename file", 
            message: "Enter the new filename:", 
            confirmText: "Rename", 
            cancelText: "Cancel",
            defaultValue: entry.filename || "",
        })
        if (!newFilename) return
        if (newFilename === entry.filename) return

        const parent = parentFromPath(entry.path)
        const newPath = resolvePath(parent, newFilename)
        await moveFile(entry.path, newPath)
    }

    function closeFileContextMenu() {
        filesState.ui.fileContextMenuPopoverOpen = false
    }

    function onClickSelectCheckbox(path: string) {
        const isSelected = filesState.selectedEntries.currentList.includes(path)
        if (isSelected) {
            filesState.selectedEntries.unselect(path)

            // Select folder if 0 files are selected
            if (filesState.selectedEntries.count === 0 && !filesState.isSearchOpen) {
                if (filesState.data.folderMeta) {
                    filesState.selectedEntries.addSelected(filesState.data.folderMeta.path)
                }
            }
        } else {
            if (filesState.data.folderMeta) {
                filesState.selectedEntries.unselect(filesState.data.folderMeta.path)
            }
            filesState.selectedEntries.addSelected(path)
        }
    }


    /**
     * # Drag and dropping
    */
    let draggedPaths: string[] | null = null
    let dragImageElement = $state() as HTMLParagraphElement
    let dragging = $state(false)

    function event_dragOver(e: DragEvent, entry: FullFileMetadata) {
        const element = e.currentTarget as HTMLElement

        const isFolder = entry.fileType === "FOLDER" || entry.fileType === "FOLDER_LINK" && appState.followSymlinks
        const isDropArea = draggedPaths && !draggedPaths.includes(entry.path)

        if (isFolder && isDropArea) {
            e.preventDefault()        
            element.classList.add("dragover")
        }
    }

    function event_dragLeave(e: DragEvent, entry: FullFileMetadata) {
        const element = e.currentTarget as HTMLElement
        element.classList.remove("dragover")
    }

    function event_drop(e: DragEvent, entry: FullFileMetadata) {
        if (entry.fileType === "FOLDER_LINK" && !appState.followSymlinks || entry.fileType !== "FOLDER") return
        if (!entry.permissions?.includes("WRITE")) return
        if (draggedPaths == null || draggedPaths.length < 1) return

        if (draggedPaths.includes(entry.path)) return

        if (draggedPaths.length > 1) {
            moveMultipleFiles(entry.path, draggedPaths)
        } else {
            const movedPath = draggedPaths[0]
            const newPath = resolvePath(entry.path, filenameFromPath(movedPath))
            moveFile(movedPath, newPath)
        }
    }

    function event_dragStart(e: DragEvent, entry: FullFileMetadata) {
        if (filesState.isSearchOpen) return
        dragging = true
        const isEntrySelected = filesState.selectedEntries.list.includes(entry.path)
        if (!isEntrySelected) {
            draggedPaths = [entry.path]
        } else {
            draggedPaths = filesState.selectedEntries.list
        }
        
        if (e.dataTransfer && dragImageElement) {
            dragImageElement.style.display = "block"
            dragImageElement.textContent = `Move ${draggedPaths!.length} file${draggedPaths!.length > 1 ? 's':''}`
            e.dataTransfer!.setDragImage(dragImageElement, 0, 0)

            setTimeout(() => {
                dragImageElement.style.display = "none"
            })
        }
    }

    function event_dragEnd(e: DragEvent, entry: FullFileMetadata) {
        draggedPaths = null
        dragging = false
    }

</script>


{#if filesState.search.sortedEntries}
    {#if filesState.search.sortedEntries.length > 0 || filesState.search.isLoading}
        <div 
            class="
                w-full h-fit absolute top-0 left-0 z-10
                {filesState.path !== filesState.search.searchPath ? 'max-h-full overflow-y-hidden opacity-0' : ''}
            "
        >
            <FileList
                sortedEntries={filesState.search.sortedEntries}
                {event_dragStart}
                {event_dragOver}
                {event_dragLeave}
                {event_drop}
                {event_dragEnd}
                {entryOnClick}
                {onClickSelectCheckbox}
                {option_rename}
                {option_move}
                {option_copy}
                {option_delete}
                {option_details}
                {option_save}
                closeFileContextMenuPopover={closeFileContextMenu}
            ></FileList>
        </div>
    {:else}
        <div class="center absolute top-0 left-0 z-10">
            <p>No files have been found.</p>
        </div>
    {/if}

    {#if filesState.search.isLoading}
        <div class="center absolute top-0 left-0 z-20 pointer-events-none">
            <Loader />
        </div>
    {/if}
{/if}

{#if filesState.data.sortedEntries && filesState.data.sortedEntries.length > 0}
    <div 
        class="
            {filesState.isSearchOpen ? 'max-h-full overflow-y-hidden opacity-0' : ''}
        "
    >
        <FileList
            sortedEntries={filesState.data.sortedEntries}
            {event_dragStart}
            {event_dragOver}
            {event_dragLeave}
            {event_drop}
            {event_dragEnd}
            {entryOnClick}
            {onClickSelectCheckbox}
            {option_rename}
            {option_move}
            {option_copy}
            {option_delete}
            {option_details}
            {option_save}
            closeFileContextMenuPopover={closeFileContextMenu}
        ></FileList>
    </div>
{:else if filesState.data.sortedEntries && filesState.data.sortedEntries.length === 0}
    {#if filesState.meta.type === "allShared"}
        <div class="center">
            <p>No files have been shared.</p>
        </div>
    {:else if filesState.meta.type === "accessible"}
        <div class="center">
            <p>You don't have access to any files.</p>
        </div>
    {:else if filesState.meta.type === "saved"}
        <div class="center">
            <p>No files have been saved.</p>
        </div>
    {:else}
        <div class="center">
            <p>This folder is empty.</p>
        </div>
    {/if}
{:else}
    <div class="center">
        <p>No folder is open.</p>
    </div>
{/if}

{#if uploadState.count > 0 && uploadState.panelOpen}
    <div class="
        fixed z-10 h-full w-full top-0 left-0 pb-4 pointer-events-none  flex items-end justify-end
        pr-[1rem] pl-[1rem]
        {filesState.ui.detailsOpen ? "lg:pr-[calc(1rem+var(--spacing-details-sidebar))]" : ""}
    ">
        <div class="w-[36rem] h-fit max-h-full max-w-full pointer-events-auto">
            <UploadPanel></UploadPanel>
        </div>
    </div>
{/if}

<FolderTreeSelector></FolderTreeSelector>

<p bind:this={dragImageElement} class="fixed top-[100vh] right-[100vw] py-4 px-6 rounded-lg whitespace-nowrap bg-neutral-300 dark:bg-neutral-800" style="display: none">-</p>


<style lang="postcss">
    :global(.file-row-grid) {
        @apply grid grid-cols-[minmax(0,1fr)_2.5rem] sm:grid-cols-[minmax(0,1fr)_9.6rem_2.5rem] md:grid-cols-[minmax(0,1fr)_9.6rem_4.2rem_2.5rem];
    }

    :global(.dragover) {
        @apply dark:bg-neutral-700;
    }
</style>