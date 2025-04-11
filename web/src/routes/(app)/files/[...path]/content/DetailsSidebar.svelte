<script lang="ts">
    import { filenameFromPath, formatBytes, formatUnixMillis, formData, handleError, handleErrorResponse, handleException, isBlank, safeFetch, sortArrayByNumber } from "$lib/code/util/codeUtil.svelte";
    import { onDestroy, onMount } from "svelte";
    import { filesState } from "./code/filesState.svelte";
    import type { ulid } from "$lib/code/types";
    import type { EntityPermission, FilePermission, MiniUser, PermissionType, Role } from "$lib/code/auth/types";
    import { hasAnyPermission } from "$lib/code/module/permissions";
    import Loader from "$lib/component/Loader.svelte";
    import { getRole } from "$lib/code/util/stateUtils";
    import { fade } from "svelte/transition";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import { Dialog } from "$lib/component/bits-ui-wrapper";
    import FilePermissionCreator from "./FilePermissionCreator.svelte";
    import { stringify } from "postcss";
    import FilePermissionEditor from "./FilePermissionEditor.svelte";
    import type { EntityPermissionMeta } from "./code/types";

    type PermissionData = {
        permissions: EntityPermission[],
        owner: ulid,
        miniUserList: Record<ulid, string>
    }

    let abortController = new AbortController()
    let permissionData: PermissionData | null = $state(null)
    let permissionDataLoading = $state(false)
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
        const selected = filesState.selectedEntry.path
        if (!selected) return
        if (!filesState.ui.detailsOpen) return
        if (lastLoaded === selected) return
        permissionData = null

        abortController.abort()
        abortController = new AbortController()

        if (hasAnyPermission(["MANAGE_ALL_FILE_PERMISSIONS", "MANAGE_OWN_FILE_PERMISSIONS"])) {
            permissionDataLoading = true
            loadPermissionData(selected, abortController.signal).then(() => {
                if (selected !== filesState.selectedEntry.path) return
                lastLoaded = selected
                permissionDataLoading = false
            })
        }
    })

    onDestroy(() => {
        if (abortController) {
            abortController.abort()
        }
    })

    async function loadPermissionData(path: string, signal: AbortSignal) {
        const response = await safeFetch(`/api/v1/permission/entity`, { body: formData({ path: path, "include-mini-user-list": true }), signal: signal })
        if (response.failed) {
            if (response.exception.name === "AbortError") return
            handleException(`Failed to fetch permission data for path ${path}`, `Failed to load file permissions.`, response.exception)
            return
        }
        const json = response.json()
        const status = response.code

        if (status.ok) {
            const miniUsers = json.miniUserList
            json.miniUserList = {}
            miniUsers.forEach((v: MiniUser) => {
                json.miniUserList[v.userId] = v.username
            })
            permissionData = json
        } else if (status.serverDown) {
            handleError(`Server ${status} when fetching file permissions`, `Failed to load file permissions. Server is unavailable.`)
        } else {
            handleErrorResponse(json, `Failed to load file permissions.`)
        }
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
        
    }

</script>


<div class="h-full w-full flex flex-col gap-6 py-6 bg-neutral-200 dark:bg-neutral-850 min-h-0">
    {#if filesState.metaLoading}
        <div></div>
    {:else if filesState.selectedEntry.meta || filesState.data.meta}
        {@const file = (filesState.selectedEntry.meta || filesState.data.meta)!}
        {@const filename = filenameFromPath(file.filename) || "/"}

        <div class="w-full flex flex-col px-6 shrink-0 flex-none">
            <h3 title={filename} class="truncate text-lg">{filename}</h3>
        </div>

        <hr class="basic-hr shrink-0 flex-none">
        
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

        <div class="w-full flex flex-col gap-6 flex-auto min-h-0 max-h-fit">
            <div class="flex w-full justify-between items-center px-6 h-[2.5rem] flex-none">
                <h4 class="">Permissions</h4>

                <!-- Create permission button -->
                <Dialog.Root bind:open={permissionCreatorOpen}>
                    <Dialog.Trigger>
                        <button disabled={!permissionData} title="Create a permission for this file" class="size-[2.5rem] p-3 rounded-lg bg-neutral-300 hover:bg-neutral-400/50 dark:bg-neutral-700/50 dark:hover:bg-neutral-700 disabled:opacity-70 duration-150">
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
                                        <div class="rounded-lg hover:bg-neutral-300 dark:hover:bg-neutral-700 h-[2.5rem] p-2">
                                            <CloseIcon></CloseIcon>
                                        </div>
                                    </Dialog.Close>
                                </div>
                                {#if filesState.selectedEntry.path}
                                    <FilePermissionCreator path={filesState.selectedEntry.path} onFinish={onFilePermissionCreated} excludedRoles={existing!.roles} excludedUsers={existing!.users}></FilePermissionCreator>
                                {/if}
                            </div>
                        </Dialog.Content>
                    </Dialog.Portal>
                </Dialog.Root>
            </div>

            <div class="flex flex-col bg-neutral-400/50 dark:bg-neutral-900 rounded px-2 py-2 mx-2 overflow-y-auto min-h-[3rem] flex-auto custom-scrollbar">
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
                            <button on:click={() => { onPermissionClicked(meta) }} class="flex flex-col gap-1 rounded bg-neutral-300 hover:ring-2 ring-blue-500 w-full px-2 py-1">
                                <p>{meta.permission.permissionType}: {meta.username ?? meta.role!.name}</p>
                                <div class="flex gap-x-4 flex-wrap">
                                    {#each meta.permission.permissions as perm}
                                        <p class="rounded bg-neutral-400/30 px-2 py-1 text-sm">{perm}</p>
                                    {/each}
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
                                                    <div class="rounded-lg hover:bg-neutral-300 dark:hover:bg-neutral-700 h-[2.5rem] p-2">
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
                        <p>No permissions</p>
                    </div>
                {:else if permissionDataLoading}
                    <!-- <div in:fade={{duration:75}} class="center py-2">
                        <Loader></Loader>
                    </div> -->
                {:else}
                    <div in:fade={{duration: 75}} class="center">
                        <p>Failed to load permissions.</p>
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