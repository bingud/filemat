<script lang="ts">
    import type { EntityPermission, FilePermission, MiniUser } from "$lib/code/auth/types";
    import { filePermissionMeta } from "$lib/code/data/permissions";
    import { loadMiniUsers } from "$lib/code/module/users";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { filesState } from "$lib/code/stateObjects/filesState.svelte";
    import type { ulid } from "$lib/code/types";
    import { forEachObject, formData, handleError, handleErrorResponse, handleException, keysOf, run, safeFetch, valuesOf } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte";
    import UploadPanel from "./elements/UploadPanel.svelte";

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
    let selectedPermissions = $state({}) as Record<FilePermission, boolean>

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

            const body = formData({ mode: selectedMode, id: selectedId, permissionList: JSON.stringify(permissionList), path: path })
            const response = await safeFetch(`/api/v1/permission/create-entity`, { body: body })
            if (response.failed) {
                handleException(`Failed to create file permission`, `Failed to create permission.`, response.exception)
                return
            }
            const status = response.code
            const json = await response.json()

            if (status.ok) {
                const newPermission = json as EntityPermission

                let target = run(() => {
                    if (mode.user) {
                        return { user: miniList!.find(v => v.userId === selectedId)!, roleId: null }
                    } else {
                        return { user: null, roleId: selectedId! }
                    }
                })

                onCompleted(newPermission, target)
            } else if (status.serverDown) {
                handleError(`Server ${status} when creating file permission`, `Failed to create permission. Server is unavailable.`)
                return
            } else {
                handleErrorResponse(json, `Failed to create permission.`)
                return
            }
        } finally {
            createPermissionLoading = false
        }
    }
</script>


<div class="size-full max-h-[80svh] flex flex-col gap-4">
    <div class="flex items-center gap-6 select-none">
        <p>For: </p>
        <div class="flex h-[2rem] rounded-lg">
            <button disabled={createPermissionLoading} class="h-full w-1/2 px-4 rounded-l-lg bg-neutral-300 dark:bg-neutral-800 {mode.user ? 'inset-ring-2 inset-ring-blue-500 bg-neutral-400/50 dark:bg-neutral-600' : ''}" aria-pressed={mode.user} on:click={() => selectedMode = "USER"}>User</button>
            <button disabled={createPermissionLoading} class="h-full w-1/2 px-4 rounded-r-lg bg-neutral-300 dark:bg-neutral-800 {mode.role ? 'inset-ring-2 inset-ring-blue-500 bg-neutral-400/50 dark:bg-neutral-600' : ''}" aria-pressed={mode.role} on:click={() => selectedMode = "ROLE"}>Role</button>
        </div>
    </div>

    <div class="max-w-full rounded-lg py-3 overflow-y-auto max-h-[25rem] flex flex-col bg-neutral-300 dark:bg-neutral-800 w-fit min-w-[10rem]">
        {#if mode.user}
            <!-- Check for list of available users -->
            {#if miniList}
                {#each miniList as mini}
                    <button on:click={() => { selectedId = mini.userId }} class:selected={mini.userId === selectedId} class="px-3 py-1 w-fit min-w-full text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700/70">{mini.username}</button>
                <!-- No users available -->
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
            {#each appState.roleList! as role}
                <button on:click={() => { selectedId = role.roleId }} class:selected={role.roleId === selectedId} class="px-3 py-1 w-fit min-w-full text-start hover:bg-neutral-400/50 dark:hover:bg-neutral-700/70">{role.name}</button>
            {/each}
        {/if}
    </div>

    <hr class="basic-hr">

    <div class="flex flex-col gap-4 select-none">

        <div class="flex flex-col gap-1">
            {#each (valuesOf(filePermissionMeta)) as perm}
                {@const id = `input-permission-${perm.id}`}
                <div class="flex gap-2 items-center cursor-pointer">
                    <input bind:checked={selectedPermissions[perm.id]} id={id} type="checkbox">
                    <label for={id}>{perm.name}</label>
                </div>
            {/each}
        </div>
    </div>

    <button disabled={!canCreatePermission || createPermissionLoading} on:click={createPermission} class="w-full rounded-lg py-2 bg-neutral-300 dark:bg-neutral-700 hover:bg-neutral-400 dark:hover:bg-neutral-600 disabled:opacity-50">{#if !createPermissionLoading}Create permission{:else}Creating...{/if}</button>
</div>

<style>
    @import "/src/app.css" reference;

    .selected {
        @apply bg-blue-500/20 inset-ring-2 inset-ring-blue-500;
    }
</style>