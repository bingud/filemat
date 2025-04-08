<script lang="ts">
    import { filenameFromPath, formatBytes, formatUnixMillis, formData, handleError, handleErrorResponse, handleException, isBlank, safeFetch, sortArrayByNumber } from "$lib/code/util/codeUtil.svelte";
    import { onDestroy, onMount } from "svelte";
    import { filesState } from "./code/filesState.svelte";
    import type { ulid } from "$lib/code/types";
    import type { FilePermission, MiniUser, PermissionType } from "$lib/code/auth/types";
    import { hasAnyPermission } from "$lib/code/module/permissions";
    import Loader from "$lib/component/Loader.svelte";
    import { getRole } from "$lib/code/util/stateUtils";
    import { fade } from "svelte/transition";

    type EntityPermission = {
        permissionId: ulid,
        permissionType: "USER" | "ROLE",
        entityId: ulid,
        userId: ulid | null,
        roleId: ulid | null,
        permissions: FilePermission[],
        createdDate: number,
    }
    type PermissionData = {
        permissions: EntityPermission[],
        owner: ulid,
        miniUserList: Record<ulid, string>
    }

    let abortController = new AbortController()
    let permissionData: PermissionData | null = $state(null)
    let permissionDataLoading = $state(false)

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
            console.log(json)
        } else if (status.serverDown) {
            handleError(`Server ${status} when fetching file permissions`, `Failed to load file permissions. Server is unavailable.`)
        } else {
            handleErrorResponse(json, `Failed to load file permissions.`)
        }
    }

</script>


<div class="h-full w-full flex flex-col gap-6 py-6 bg-neutral-200 dark:bg-neutral-850 min-h-0">
    {#if filesState.metaLoading}
        <div></div>
    {:else if filesState.selectedEntry.meta || filesState.data.meta}
        {@const file = (filesState.selectedEntry.meta || filesState.data.meta)!}

        <div class="w-full flex flex-col px-6 shrink-0 flex-none">
            <h3 class="truncate text-lg">{filenameFromPath(file.filename) || "/"}</h3>
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
            <p class="px-6 flex-none">Permissions</p>

            <div class="flex flex-col bg-neutral-900 rounded-lg px-4 py-4 mx-2 overflow-y-auto min-h-[5rem] flex-auto custom-scrollbar">
                {#if permissionData && permissionData.permissions.length < 0}
                    <!-- User Permissions -->
                    {#each permissionData.permissions.filter(v => v.permissionType === "USER") as meta}
                        {@const username = permissionData.miniUserList[meta.userId!]}

                        <div class="flex flex-col gap-1">
                            <p>{username}</p>
                            <div class="flex gap-x-4 flex-wrap">
                                {#each meta.permissions as perm}
                                    <p>{perm}</p>
                                    <!-- <div class="flex gap-1 items-center">
                                        <input id="id-{perm}" type="checkbox">
                                        <label for="id-{perm}">{perm}</label>
                                    </div> -->
                                {/each}
                            </div>
                        </div>
                    {/each}

                    <!-- Role permissions -->
                    {#each permissionData.permissions.filter(v => v.permissionType === "ROLE") as meta}
                        {@const role = getRole(meta.roleId!)}

                        <div class="flex flex-col gap-1">
                            <p>{role!.name}</p>
                            <div class="flex gap-x-4 flex-wrap">
                                {#each meta.permissions as perm}
                                    <p>{perm}</p>
                                    <!-- <div class="flex gap-1 items-center">
                                        <input id="id-{perm}" type="checkbox">
                                        <label for="id-{perm}">{perm}</label>
                                    </div> -->
                                {/each}
                            </div>
                        </div>
                    {/each}
                {:else if permissionData && permissionData.permissions.length === 0}
                    <div class="center">
                        <p>No permissions</p>
                    </div>
                {:else if permissionDataLoading}
                    <div class="center py-2">
                        <Loader></Loader>
                    </div>
                {:else}
                    <div class="center">
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