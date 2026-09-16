<script lang="ts">
    import type { EntityPermission, FilePermission, MiniUser } from "$lib/code/auth/types";
    import { filePermissionMeta } from "$lib/code/data/permissions";
    import { loadMiniUsers } from "$lib/code/module/users";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import type { ulid } from "$lib/code/types/types";
    import { forEachObject, formData, handleErr, mapToObject, run, safeFetch, valuesOf } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import AllowDenySwitch from "./AllowDenySwitch.svelte";

    let {
        excludedRoles, 
        excludedUsers,
        onFinish: onCompleted,
        path,
    }: {
        excludedRoles: ulid[],
        excludedUsers: ulid[],
        onFinish: (perm: EntityPermission, target: {user: MiniUser | null, roleId: ulid | null}) => any,
        path: string,
    } = $props()

    let selectedMode = $state('USER') as 'USER' | 'ROLE'
    let mode = $derived(selectedMode === 'USER' ? { user: true, role: false } : { user: false, role: true })

    let selectedId = $state(null) as ulid | null
    const permissions = valuesOf(filePermissionMeta)
    let selectedPermissions = $state(mapToObject(permissions, (v) => {
        return { key: v.id, value: false }
    })) as Record<FilePermission, boolean>

    let allValue = $derived.by(() => {
        const values = permissions.map((p) => selectedPermissions[p.id])
        if (values.every((v) => v === true)) return true
        if (values.every((v) => v === false)) return false
        return null
    })

    function setAll(allowed: boolean) {
        for (const perm of permissions) {
            selectedPermissions[perm.id] = allowed
        }
    }

    /**
     * Users that dont have a file role yet
     */
    let miniList = $state(null) as null | MiniUser[]
    let miniListLoading = $state(false)

    let createPermissionLoading = $state(false)

    let canCreatePermission = $derived(selectedId != null)

    $effect(() => {
        if (mode.user) {
            onSelectUser()
        }
        selectedId = null
    })


    async function onSelectUser() {
        if (miniList || miniListLoading) return
        miniListLoading = true

        const list = await loadMiniUsers(null, true)
        if (list) {
            miniList = list.filter(v => !excludedUsers.includes(v.userId))
        }
        miniListLoading = false
    }

    async function createPermission() {
        if (createPermissionLoading) return
        createPermissionLoading = true

        try {
            const permissionList: FilePermission[] = []
            forEachObject(selectedPermissions, (k, v) => {
                if (v === true) permissionList.push(k)
            })

            const body = formData({
                mode: selectedMode,
                id: selectedId,
                permissionList: JSON.stringify(permissionList),
                path: path
            })
            const response = await safeFetch(`/api/v1/permission/create-entity`, { body: body })
            if (response.failed) {
                handleErr({
                    description: `Failed to create file permission`,
                    notification: `Failed to create permission.`,
                })
                return
            }
            const status = response.code
            const json = await response.json()

            if (status.notFound) {
                handleErr({
                    description: `File not found when creating permission`,
                    notification: `This file was not found.`
                })
                return
            } else if (status.failed) {
                handleErr({
                    description: `Failed to create permission.`,
                    notification: json.message || `Failed to create permission.`,
                    isServerDown: status.serverDown
                })
                return
            }

            const newPermission = json as EntityPermission
            let target = run(() => {
                if (mode.user) {
                    return { user: miniList!.find(v => v.userId === selectedId)!, roleId: null }
                } else {
                    return { user: null, roleId: selectedId! }
                }
            })

            onCompleted(newPermission, target)
        } finally {
            createPermissionLoading = false
        }
    }
</script>


<div class="w-full flex flex-col gap-4">
    <div class="flex items-center gap-6 select-none">
        <p>For: </p>
        <div class="flex h-[2rem] rounded-lg">
            <button disabled={createPermissionLoading} class="h-full w-1/2 px-4 rounded-l-lg bg-surface-content-button {mode.user ? 'inset-ring-2 inset-ring-blue-500' : ''}" aria-pressed={mode.user} onclick={() => selectedMode = "USER"}>User</button>
            <button disabled={createPermissionLoading} class="h-full w-1/2 px-4 rounded-r-lg bg-surface-content-button {mode.role ? 'inset-ring-2 inset-ring-blue-500' : ''}" aria-pressed={mode.role} onclick={() => selectedMode = "ROLE"}>Role</button>
        </div>
    </div>

    <div class="w-full rounded-lg py-3 overflow-y-auto custom-scrollbar flex flex-col bg-surface-content sm:max-h-[25rem]">
        {#if mode.user}
            <!-- Check for list of available users -->
            {#if miniList}
                {#each miniList as mini (mini.userId)}
                    <button onclick={() => { selectedId = mini.userId }} class:selected={mini.userId === selectedId} class="px-3 py-1 w-fit min-w-full text-start bg-surface-content-button">{mini.username}</button>
                {:else}
                    <p class="px-3 py-1">No users available.</p>
                {/each}
            {:else if miniListLoading}
                <div class="center">
                    <div class="size-[2rem]">
                        <Loader></Loader>
                    </div>
                </div>
            {:else}
                <p>Failed to load available users.</p>
            {/if}
        {:else}
            {#each appState.roleList!.filter(r => !excludedRoles.includes(r.roleId)) as role (role.roleId)}
                <button onclick={() => { selectedId = role.roleId }} class:selected={role.roleId === selectedId} class="px-3 py-1 w-full text-start bg-surface-content-button">{role.name}</button>
            {:else}
                <p class="px-3 py-1">No roles available.</p>
            {/each}
        {/if}
    </div>

    <div class="flex flex-col gap-2 select-none mb-2">
        <div class="flex items-center gap-4 px-1 py-1">
            <AllowDenySwitch value={allValue} onchange={setAll} />
            <span>All</span>
        </div>

        <hr class="basic-hr">

        {#each permissions as permission (permission.id)}
            <div class="flex items-center gap-4 px-1 py-1">
                <AllowDenySwitch
                    value={selectedPermissions[permission.id]}
                    onchange={(allowed) => {
                        selectedPermissions[permission.id] = allowed
                    }}
                />
                <span>{permission.name}</span>
            </div>
        {/each}
    </div>

    <button disabled={!canCreatePermission || createPermissionLoading} onclick={createPermission} class="w-full rounded-lg py-2 mt-2 bg-surface-content-button disabled:opacity-50">{#if !createPermissionLoading}Create permission{:else}Creating...{/if}</button>
</div>

<style lang="postcss">
    @import "/src/app.css" reference;

    .selected {
        @apply bg-blue-500/20 inset-ring-2 inset-ring-blue-500;
    }
</style>
