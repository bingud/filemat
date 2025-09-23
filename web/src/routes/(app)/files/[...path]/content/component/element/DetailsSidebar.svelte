<script lang="ts">
    import { filenameFromPath, formatBytes, formatUnixMillis, formData, handleException, isBlank, safeFetch, sortArrayByNumber, debounceFunction, handleErr, isFolder } from "$lib/code/util/codeUtil.svelte";
    import { onDestroy } from "svelte";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import type { ulid } from "$lib/code/types/types";
    import type { EntityPermission, FilePermission, MiniUser, PermissionType, Role } from "$lib/code/auth/types";
    import { hasAnyPermission } from "$lib/code/module/permissions";
    import { getRole } from "$lib/code/util/stateUtils";
    import { fade } from "svelte/transition";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import { Dialog } from "$lib/component/bits-ui-wrapper";
    import FilePermissionCreator from "./FilePermissionCreator.svelte";
    import FilePermissionEditor from "./FilePermissionEditor.svelte";
    import type { EntityPermissionMeta } from "../../code/types"; 
    import { filePermissionCount, filePermissionMeta } from "$lib/code/data/permissions";
    import { dev } from "$app/environment";
    import RoleIcon from "$lib/component/icons/RoleIcon.svelte";
    import UserIcon from "$lib/component/icons/UserIcon.svelte";

    type PermissionData = {
        permissions: EntityPermission[],
        owner: ulid,
        miniUserList: Record<ulid, string>
    }

    let abortController = new AbortController()
    let permissionData: PermissionData | null = $state(null)
    let permissionDataLoading = $state(false)
    let permissionDataDebounced = $state(false)
    let permissionCreatorOpen = $state(false)

    let editedPermission: EntityPermissionMeta | null = $state(null)

    // Role and user IDs that already have a file permission
    let existing = $derived.by(() => {
        if (!permissionData) return null
        let users: ulid[] = []
        let roles: ulid[] = []

        permissionData.permissions.forEach(v => {
            if (v.permissionType === "USER") {
                users.push(v.userId!)
            } else {
                users.push(v.roleId!)
            }
        })

        return { users: users, roles: roles }
    })

    let lastLoaded = ""
    $effect(() => {
        const selectedPath = filesState.selectedEntries.single

        if (!selectedPath) return
        if (!filesState.ui.detailsOpen) return
        if (lastLoaded === selectedPath) return

        permissionData = null
        permissionDataDebounced = true
        
        loadPermissionDataDebounced(selectedPath)
    })

    // Debounced function to load permission data
    const loadPermissionDataDebounced = debounceFunction(async (path: string) => {
        permissionDataDebounced = false

        abortController.abort()
        abortController = new AbortController()
    
        if (hasAnyPermission(["MANAGE_ALL_FILE_PERMISSIONS", "MANAGE_OWN_FILE_PERMISSIONS"])) {
            permissionDataLoading = true
            await loadPermissionData(path, abortController.signal)
            if (path !== filesState.selectedEntries.single) return
            lastLoaded = path
            permissionDataLoading = false
        }
    }, 100, 5000)

    onDestroy(() => {
        if (abortController) {
            abortController.abort()
        }
    })

    /**
     * Loads the permission data for the given path
     * @param path - The path to load the permission data for
     * @param signal - The abort signal
     */
    async function loadPermissionData(path: string, signal: AbortSignal) {
        const response = await safeFetch(`/api/v1/permission/entity`, {
            body: formData({ path: path, "include-mini-user-list": true }),
            signal: signal
        })
        if (response.failed) {
            if (response.exception.name === "AbortError") return
            handleErr({
                description: `Failed to fetch permission data for path ${path}`,
                notification: `Failed to load file permissions.`
            })
            return
        }
        const json = response.json()
        const status = response.code

        if (status.notFound) {
            handleErr({
                description: `File not found when getting permission data`,
                notification: `This file was not found.`
            })
            return
        } else if (status.failed) {
            handleErr({
                description: `Failed to load file permissions.`,
                notification: json.message || `Failed to load file permissions.`,
                isServerDown: status.serverDown
            })
            return
        }

        if (path !== filesState.selectedEntries.single) return

        const miniUsers = json.miniUserList
        json.miniUserList = {}
        miniUsers.forEach((v: MiniUser) => {
            json.miniUserList[v.userId] = v.username
        })
        permissionData = json
    }

    function onFilePermissionCreated(perm: EntityPermission, target: { user: MiniUser | null, roleId: ulid | null }) {
        if (permissionData) {
            permissionData.permissions.push(perm)

            if (target.user) {
                permissionData.miniUserList[target.user.userId] = target.user.username
            }

            permissionCreatorOpen = false
        }
    }

    function onPermissionClicked(perm: EntityPermissionMeta) {
        editedPermission = perm
    }
    function onPermissionUpdated(id: ulid, newPermissions: FilePermission[] | null, deleted: boolean) {
        if (permissionData) {
            if (deleted) {
                // Remove the permission from the list if it was deleted
                permissionData.permissions = permissionData.permissions.filter(p => p.permissionId !== id);
            } else {
                // Update the permissions array for the modified permission
                const permissionIndex = permissionData.permissions.findIndex(p => p.permissionId === id);
                if (permissionIndex !== -1 && newPermissions) {
                    permissionData.permissions[permissionIndex].permissions = newPermissions;
                }
            }
            
            // Reset the edited permission after update
            editedPermission = null;
        }
    }

</script>


<div class="h-full w-full flex flex-col gap-6 py-6 bg-neutral-200 dark:bg-neutral-850 min-h-0">
    {#if filesState.metaLoading}
        <div></div>
    {:else if filesState.selectedEntries.hasMultiple}
        <div class="w-full flex flex-col px-6 shrink-0 flex-none">
            <h3 class="truncate text-lg">Multiple files selected</h3>
        </div>
    {:else if filesState.selectedEntries.singleMeta}
        {@const file = filesState.selectedEntries.singleMeta}
        {@const filename = filenameFromPath(file.path) || "/"}

        <div class="w-full flex flex-col px-6 shrink-0 flex-none">
            <h3 title={filename} class="truncate text-lg">{filename}</h3>
        </div>

        <hr class="basic-hr shrink-0 flex-none">

        {#if isFolder(file) && !file.isExecutable}
            <p class="px-6 text-sm">Missing permission to open this folder</p>
            <hr class="basic-hr shrink-0 flex-none">
        {/if}
        
        <div class="w-full flex flex-col px-6 gap-6 flex-none">
            <div class="detail-container">
                <p class="detail-title">File Size</p>
                <p>{formatBytes(file.size)}</p>
            </div>

            <div class="detail-container">
                <p class="detail-title">Last modified at</p>
                <p>{formatUnixMillis(file.modifiedDate)}</p>
            </div>

            <div class="detail-container">
                <p class="detail-title">Created at</p>
                <p>{formatUnixMillis(file.createdDate)}</p>
            </div>
        </div>

        <hr class="basic-hr flex-none">

        <!-- Permissions -->
        <div class="w-full flex flex-col gap-6 flex-auto min-h-0 max-h-fit">
            <div class="flex w-full justify-between items-center px-6 h-[2.5rem] flex-none">
                <h4 class="">Permissions</h4>

                <!-- Create permission button -->
                <Dialog.Root bind:open={permissionCreatorOpen}>
                    <Dialog.Trigger>
                        <button disabled={!permissionData} title="Create a permission for this file" class="size-[2.5rem] p-2 rounded-md bg-neutral-200 hover:bg-neutral-300 dark:bg-neutral-700 dark:hover:bg-neutral-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors duration-150 shadow-sm">
                            <div class="size-full aspect-square rotate-45">
                                <CloseIcon></CloseIcon>
                            </div>
                        </button>
                    </Dialog.Trigger>

                    <Dialog.Portal>
                        <Dialog.Overlay
                            class="fixed inset-0 z-50 bg-black/50"
                        />
                        <Dialog.Content>
                            <div class="rounded-lg bg-neutral-50 dark:bg-neutral-900 shadow-popover fixed left-[50%] top-[50%] z-50 w-[30rem] max-w-[calc(100%-2rem)] translate-x-[-50%] translate-y-[-50%] p-5 flex flex-col gap-4">
                                <div class="flex items-center justify-between w-full">
                                    <h3>Create a file permission</h3>
                                    <Dialog.Close>
                                        <div class="rounded-md hover:bg-neutral-300 dark:hover:bg-neutral-700 h-[2.5rem] aspect-square p-2">
                                            <CloseIcon></CloseIcon>
                                        </div>
                                    </Dialog.Close>
                                </div>
                                {#if filesState.selectedEntries.single}
                                    <FilePermissionCreator path={filesState.selectedEntries.single} onFinish={onFilePermissionCreated} excludedRoles={existing!.roles} excludedUsers={existing!.users}></FilePermissionCreator>
                                {/if}
                            </div>
                        </Dialog.Content>
                    </Dialog.Portal>
                </Dialog.Root>
            </div>

            <div class="flex flex-col bg-neutral-400/50 dark:bg-neutral-900 rounded-md px-2 py-2 mx-2 overflow-y-auto min-h-[3rem] flex-auto custom-scrollbar">
                {#if permissionData && permissionData.permissions.length > 0}
                    <div in:fade={{duration: 75}} class="w-fill min-h-full h-fit flex flex-col gap-1">
                        <!-- User Permissions -->
                        {#each permissionData.permissions.filter(v => v.permissionType === "USER") as meta}
                            {@const username = permissionData.miniUserList[meta.userId!]}
                            {@render permissionCard({ permission: meta, username: username, role: null } as EntityPermissionMeta)}
                        {/each}

                        <!-- Role permissions -->
                        {#each permissionData.permissions.filter(v => v.permissionType === "ROLE") as meta}
                            {@const role = getRole(meta.roleId!)}
                            {@render permissionCard({ permission: meta, username: null, role: role} as EntityPermissionMeta)}
                        {/each}

                        {#snippet permissionCard(meta: EntityPermissionMeta)}
                            <button on:click={() => { onPermissionClicked(meta) }} class="flex flex-col gap-2 rounded-md bg-neutral-200 dark:bg-neutral-800 hover:ring-2 ring-blue-500 w-full px-3 py-2 shadow-sm transition-colors duration-150">
                                <div class="flex items-center gap-1">
                                    <div class="aspect-square h-[1.2rem]">
                                        {#if meta.permission.permissionType === "ROLE"}
                                            <RoleIcon></RoleIcon>
                                        {:else}
                                            <UserIcon></UserIcon>
                                        {/if}
                                    </div>
                                    <p>{meta.username ?? meta.role!.name}</p>
                                </div>
                                <div class="flex gap-2 flex-wrap">
                                    {#if meta.permission.permissions.length === filePermissionCount}
                                        <span class="inline-flex items-center rounded-md bg-neutral-300/80 dark:bg-neutral-700/80 px-2.5 py-1 text-sm shadow-sm">All Permissions</span>
                                    {:else if meta.permission.permissions.length === 0}
                                        <span class="inline-flex items-center rounded-md bg-neutral-300/80 dark:bg-neutral-700/80 px-2.5 py-1 text-sm shadow-sm">No Permissions</span>
                                    {:else}
                                        {#each meta.permission.permissions as perm}
                                            {@const meta = filePermissionMeta[perm]}
                                            <span class="inline-flex items-center rounded-md bg-neutral-300/80 dark:bg-neutral-700/80 px-2.5 py-1 text-sm shadow-sm">{meta.name}</span>
                                        {/each}
                                    {/if}
                                </div>
                            </button>
                        {/snippet}

                        {#if editedPermission}
                            <Dialog.Root onOpenChange={(open) => { if (!open) { editedPermission = null } }} open={true}>
                                <Dialog.Portal>
                                    <Dialog.Overlay
                                        class="fixed inset-0 z-50 bg-black/50"
                                    />

                                    <Dialog.Content>
                                        <div class="rounded-lg bg-neutral-50 dark:bg-neutral-900 shadow-popover fixed left-[50%] top-[50%] z-50 w-[30rem] max-w-[calc(100%-2rem)] translate-x-[-50%] translate-y-[-50%] p-5 flex flex-col gap-4">
                                            <div class="flex items-center justify-between w-full">
                                                <h3>Edit file permission</h3>
                                                <Dialog.Close>
                                                    <div class="rounded-md hover:bg-neutral-300 dark:hover:bg-neutral-700 h-[2.5rem] aspect-square p-2">
                                                        <CloseIcon></CloseIcon>
                                                    </div>
                                                </Dialog.Close>
                                            </div>
                                            
                                            <FilePermissionEditor onPermissionUpdated={onPermissionUpdated} editedPermission={editedPermission} />
                                        </div>
                                    </Dialog.Content>
                                </Dialog.Portal>
                            </Dialog.Root>
                        {/if}
                    </div>
                {:else if permissionData && permissionData.permissions.length === 0}
                    <div in:fade={{duration: 75}} class="center">
                        <p class="text-neutral-500 dark:text-neutral-400 py-2">No permissions</p>
                    </div>
                {:else if permissionDataLoading}
                    <!-- <div in:fade={{duration:75}} class="center py-2">
                        <Loader></Loader>
                    </div> -->
                {:else if permissionDataDebounced}
                    <!-- Waiting for debounce timer -->
                {:else}
                    <div in:fade={{duration: 75}} class="center">
                        <p class="text-neutral-600 dark:text-neutral-400 py-2">Failed to load permissions.</p>
                    </div>
                {/if}
            </div>
        </div>
    {:else}
        <div class="w-full h-full flex flex-col items-center pt-4">
            <p class="opacity-80">Select a file to see details</p>
        </div>
    {/if}
</div>


<style>
    @import "/src/app.css" reference;

    .detail-container {
        @apply flex flex-col;
    }

    .detail-title {
        @apply text-sm opacity-80;
    }
</style>