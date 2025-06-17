<script lang="ts">
    import { goto } from "$app/navigation";
    import { page } from "$app/state";
    import { addRoleToUser, changeRolePermissions, deleteRole } from "$lib/code/admin/roles";
    
    import { loadUserList } from "$lib/code/admin/users";
    import { PermissionType, type MiniUser, type SystemPermission, type PublicUser, type Role, type RoleMeta } from "$lib/code/auth/types";
    import { systemPermissionMeta } from "$lib/code/data/permissions";
    import { getMaxPermissionLevel, getPermissionMeta, containsPermission, hasPermissionLevel } from "$lib/code/module/permissions";
    import { loadMiniUsers } from "$lib/code/module/users";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import type { ulid } from "$lib/code/types/types";
    import { formatUnixTimestamp, formData, handleError, handleErrorResponse, handleException, includesList, pageTitle, parseJson, removeString, safeFetch, sortArrayAlphabetically, sortArrayByNumberDesc, toStatus, valuesOf } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import Loader from "$lib/component/Loader.svelte";
    import Popover from "$lib/component/Popover.svelte";
    import { onMount } from "svelte";
    import { fade } from "svelte/transition";

    // Page data
    let role = $state(null) as RoleMeta & { miniUsers: MiniUser[] } | null
    let status: "loading" | "failed" | "ready" | undefined = $state()
    let title = $derived(role ? `Manage role: ${role.name}` : "Manage role")

    // Role permission
    let highestRolePermissionLevel = $derived(role ? getMaxPermissionLevel(role.permissions) : null)

    // Adding user
    let addUsersButton: HTMLElement | undefined = $state()
    let allUsers: null | MiniUser[] = $state(null)
    let allUsersLoading = $state(false)
    let addingUser = $state(false)
    let addUserPopoverOpen = $state(false)

    // Editing
    let editingPermissions = $state(false)
    let addPermissionButton: HTMLElement | undefined = $state()
    let canEditPermissions = $derived((highestRolePermissionLevel != null && auth.permissionLevel != null) ? (highestRolePermissionLevel <= (auth.permissionLevel)) : null)
    let newPermissionList: SystemPermission[] | null = $state(null)
    let savingEditedPermissions = $state(false)

    const displayedPermissionList = $derived.by(() => {
        if (!editingPermissions) return role?.permissions
        return newPermissionList
    })

    // Deleting role
    let deletingRole = $state(false)

    onMount(() => {
        uiState.settings.title = title
    })

    $effect(() => {
        const roleId = page.params.roleId
        if (!Validator.isUlidValid(roleId)) {
            role = null
            status = "failed"
            return
        }

        status = "loading"
        loadRoleData(roleId)
    })

    async function loadRoleData(id: string) {
        const response = await safeFetch(`/api/v1/admin/role/get`, { body: formData({ roleId: id }) })
        if (response.failed) {
            role = null
            status = "failed"
            handleException(`Failed to fetch role data.`, `Failed to load role.`, response.exception)
            return
        }
        const code = response.code
        const json = response.json()

        if (code.ok) {
            if (json.userIds) {
                const miniUsers = await loadMiniUsers(json.userIds)
                json.miniUsers = miniUsers
            }

            role = json
            status = "ready"
        } else if (code.serverDown) {
            handleError(`Server ${code} while fetching role data`, `Server is unavailable.`)
            role = null
            status = "failed"
        } else if (code.notFound) {
            handleError(`role not found`, `This role was not found.`)
            role = null
            status = "failed"
        } else {
            handleErrorResponse(json, `Failed to load role.`)
            role = null
            status = "failed"
        }
    }



    /**
     * Load list of all filemat users
     */
    async function loadAllUserList() {if (allUsersLoading) return; allUsersLoading = true; try {
        allUsers = null
        const r = await loadMiniUsers(null, true)
        if (r) {
            allUsers = r
        }
    } finally { allUsersLoading = false }}

    /**
     * Assign this role to user
     */
    async function addUser(user: MiniUser) {if (addingUser) return; addingUser = true; try {
        if (!role || !user) return
        const result = await addRoleToUser(user.userId, role.roleId)
        if (result) {
            role.miniUsers.push({
                userId: user.userId,
                username: user.username
            })
            addUserPopoverOpen = false
        }
    } finally { addingUser = true }}

    /**
     * Add permission to role while editing
     */
    function addPermission(id: SystemPermission) {
        if (!newPermissionList) return
        if (newPermissionList.includes(id)) return
        newPermissionList.push(id)
    }

    /**
     * Remove permission from role while editing
     */
    function removePermission(id: SystemPermission) {
        if (!newPermissionList) return
        removeString(newPermissionList, id)
    }

    /**
     * Save new edited role permissions
     */
    async function saveEditedPermissions() {if (savingEditedPermissions) return; savingEditedPermissions = true; try {
        if (!role || !newPermissionList) return

        const result = await changeRolePermissions(role.roleId, newPermissionList)
        if (result === true) {
            role.permissions = newPermissionList
            cancelEditingPermissions()
        }
    } finally { savingEditedPermissions = false }}

    function startEditingPermissions() {
        if (!role) return
        editingPermissions = true
        newPermissionList = [...role.permissions]
    }
    function cancelEditingPermissions() {
        editingPermissions = false
        newPermissionList = null
    }

    /**
     * Delete this role
     */
    async function delRole() {if (deletingRole) return; deletingRole = true; try {
        if (!role) return
        if (!confirm(`Are you sure you want to delete role '${role.name}'?`)) return
        const result = await deleteRole(role?.roleId)
        if (result) await goto(`/settings/roles`)
    } finally { deletingRole = false }}
</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


{#if role}
    <div in:fade={{duration: 70}} class="page flex-col gap-12">
        <div class="flex flex-col gap-4 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-850">
            <h1 class="text-lg">{role.name}</h1>
            <p class="dark:text-neutral-300">Created on: {formatUnixTimestamp(role.createdDate)}</p>
            {#if appState.systemRoleIds?.admin === role.roleId}
                <p>This is a system role that was created automatically. It has all available permissions.</p>
            {:else if appState.systemRoleIds?.user === role.roleId}
                <p>This is a system role that was created automatically. Every user has this role.</p>
            {/if}
        </div>

        {#if containsPermission(auth.permissions, "MANAGE_USERS")}
            <div class="flex flex-col w-full gap-4">
                <h2 class="text-lg">Users with this role:</h2>
                
                <div class="flex flex-col gap-4 p-4 rounded-lg bg-neutral-200 dark:bg-neutral-850 w-full">
                    {#if role.miniUsers}
                        {#if role.roleId !== appState.systemRoleIds?.user}
                            <div class="flex gap-3 flex-wrap w-full">
                                {#each sortArrayAlphabetically(role.miniUsers, v => v.username) as mini}
                                    <a href="/settings/users/{mini.userId}" title={mini.userId} class="p-3 rounded bg-neutral-300 hover:bg-neutral-400 dark:bg-neutral-900 dark:hover:bg-neutral-800">{mini.username}</a>
                                {:else}
                                    <p class="opacity-80">Nobody</p>
                                {/each}
                            </div>
                        {:else}
                            <a href="/settings/users" class="p-3 rounded bg-neutral-200 dark:bg-neutral-900 hover:text-blue-400 hover:underline w-fit">All users have this role</a>
                        {/if}
                    {:else}
                        <p class="p-6 bg-neutral-300 dark:bg-neutral-800">Failed to load users with this role.</p>
                    {/if}
                </div>

                {#if highestRolePermissionLevel && hasPermissionLevel(highestRolePermissionLevel)}
                    <button bind:this={addUsersButton} on:click={() => { addUserPopoverOpen = true }} on:click={loadAllUserList} class="basic-button">Add users</button>
                {/if}
                    
                {#if addUsersButton}
                    <Popover bind:isOpen={addUserPopoverOpen} fadeDuration={40} marginRem={1} button={addUsersButton} onClose={() => {}}>
                        <div class="max-w-full w-[13rem] rounded-md bg-neutral-300 dark:bg-neutral-800 overflow-y-auto overflow-x-hidden max-h-[28rem] min-h-[2rem] h-fit">
                            {#if allUsers}
                                {#if !includesList(role.miniUsers.map(v=>v.userId), allUsers.map(v=>v.userId))}
                                    <div class="flex flex-col gap-2 p-2">
                                        {#each sortArrayAlphabetically(allUsers, v => v.username) as user}
                                            {@const hasRole = role.miniUsers.map(v => v.userId).includes(user.userId)}
                                            {#if !hasRole}
                                                <button disabled={addingUser} on:click={() => { addUser(user) }} class="rounded bg-neutral-300 hover:bg-neutral-200 dark:bg-neutral-900 dark:hover:bg-neutral-700 text-left px-2 py-1 !w-full">{user.username}</button>
                                            {/if}
                                        {/each}
                                    </div>
                                {:else}
                                    <div class="center !h-[3rem]">
                                        <p>All users have this role.</p>
                                    </div>
                                {/if}
                            {:else if allUsersLoading}
                                <div class="center !h-[3rem] py-2">
                                    <Loader></Loader>
                                </div>
                            {:else}
                                <div class="center !h-[3rem]">
                                    <p class="">Failed to load list of users.</p>
                                </div>
                            {/if}
                        </div>
                    </Popover>
                {/if}
            </div>
        {/if}

        <div class="flex flex-col gap-4">
            <h2 class="text-lg">Permissions:</h2>
            <div class="flex gap-3 flex-wrap rounded-lg w-full bg-neutral-200 dark:bg-neutral-850 p-4">
                {#if displayedPermissionList}
                    {#each sortArrayByNumberDesc(displayedPermissionList.map(v => getPermissionMeta(v)), v => v.level) as meta}
                        {#if !editingPermissions}
                            <span title={meta.description} class="px-2 py-1 bg-neutral-300 dark:bg-neutral-800 ring ring-neutral-400 dark:ring-neutral-700 rounded w-fit">
                                {meta.name}
                            </span>
                            
                        {:else}
                            <button on:click={() => { removePermission(meta.id) }} title={`Remove permission: ${meta.name}`} class="px-2 py-1 bg-neutral-300 dark:bg-neutral-800 ring ring-neutral-700 rounded w-fit hover:ring-2 hover:ring-red-400">
                                {meta.name}
                            </button>
                        {/if}
                    {:else}
                        <p class="text-sm dark:text-neutral-300 p-2 rounded bg-neutral-200 dark:bg-neutral-900">No permissions</p>
                    {/each}
                {/if}
            </div>

            {#if editingPermissions && newPermissionList && !includesList(newPermissionList, valuesOf(systemPermissionMeta).filter(v=>v.type !== PermissionType.file).map(v=>v.id))}
                <button bind:this={addPermissionButton} class="basic-button text-sm">Add permission</button>

                <Popover marginRem={1} fadeDuration={40} button={addPermissionButton} onClose={() => {}}>
                    <div class="max-w-full w-[18rem] rounded-md bg-neutral-300 dark:bg-neutral-800 overflow-y-auto overflow-x-hidden max-h-[28rem] min-h-[2rem] h-fit scrollbar">
                        <div class="flex flex-col gap-2 p-2">
                            {#each sortArrayByNumberDesc(valuesOf(systemPermissionMeta).filter(m => m.type !== PermissionType.file && newPermissionList?.includes(m.id) != true), v => v.level) as meta}
                                <button on:click={() => { addPermission(meta.id) }} class="rounded bg-neutral-300 hover:bg-neutral-200 dark:bg-neutral-900 dark:hover:bg-neutral-700 text-left px-2 py-1 !w-full">{meta.name}</button>
                            {/each}
                        </div>
                    </div>
                </Popover>

                <hr class="basic-hr max-w-full w-[10rem]">
            {/if}            

            {#if canEditPermissions}
                {#if editingPermissions === false}
                    <button on:click={startEditingPermissions} class="basic-button">{#if !editingPermissions}Edit{:else}Cancel editing{/if} permissions</button>
                {:else}
                    <div class="flex gap-3">
                        <button on:click={saveEditedPermissions} class="basic-button hover:ring-2 ring-green-400">{#if !savingEditedPermissions}Save{:else}...{/if}</button>
                        <button on:click={cancelEditingPermissions} class="basic-button hover:ring-2 ring-red-400">Cancel</button>
                    </div>
                {/if}
            {/if}
        </div>

        {#if canEditPermissions}
            <hr class="basic-hr max-w-full">

            <button on:click={delRole} class="basic-button hover:ring-2 ring-red-400">{#if !deletingRole}Delete role{:else}Deleting...{/if}</button>
        {/if}
    </div>
{:else if status === "failed"}
    <div class="py-4">
        <p class="p-6 rounded bg-neutral-300 dark:bg-neutral-800">Failed to load role data.</p>
    </div>
{:else if status === "loading"}
    <div in:fade={{duration: 400}} class="center">
        <Loader></Loader>
    </div>
{/if}


<style>
    @import "/src/app.css" reference;

</style>