<script lang="ts">
    import { dev } from "$app/environment";
    import { page } from "$app/state";
    import { addRoleToUser } from "$lib/code/admin/roles";
    
    import { loadUserList } from "$lib/code/admin/users";
    import { PermissionType, type MiniUser, type PublicUser, type Role, type RoleMeta } from "$lib/code/auth/types";
    import { formatPermission, getMaxPermissionLevel, getPermissionInfo, hasPermissionLevel } from "$lib/code/data/permissions";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import type { ulid } from "$lib/code/types";
    import { formatUnixTimestamp, formData, handleError, handleErrorResponse, handleException, includesList, pageTitle, parseJson, safeFetch, sortArrayAlphabetically, sortArrayByNumberDesc, toStatus } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import Loader from "$lib/component/Loader.svelte";
    import Popover from "$lib/component/Popover.svelte";
    import { A } from "flowbite-svelte";
    import { onMount } from "svelte";
    import { fade } from "svelte/transition";

    // undefined loading, null failed
    let role = $state(null) as RoleMeta & { miniUsers: MiniUser[] } | null
    let status: "loading" | "failed" | "ready" | undefined = $state()
    let title = $derived(role ? `Manage role: ${role.name}` : "Manage role")

    let addUsersButton: HTMLElement | undefined = $state()
    let allUsers: null | PublicUser[] = $state(null)
    let allUsersLoading = $state(false)
    let addingUser = $state(false)
    let addUserPopoverOpen = $state(false)

    let highestRolePermissionLevel = $derived(role ? getMaxPermissionLevel(role.permissions) : null)

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
        const httpStatus = response.code
        const json = response.json()

        if (httpStatus.ok) {
            if (json.userIds) {
                const miniUsers = await loadMiniUsers(json.userIds)
                json.miniUsers = miniUsers
            }

            role = json
            status = "ready"
        } else if (httpStatus.serverDown) {
            handleError(`Server ${httpStatus} while fetching role data`, `Server is unavailable.`)
            role = null
            status = "failed"
        } else {
            handleErrorResponse(json, `Failed to load role.`)
            role = null
            status = "failed"
        }
    }

    /**
     * Load list of users with this role
     */
    async function loadMiniUsers(userIds: ulid[]): Promise<MiniUser[] | null> {
        const response = await safeFetch(`/api/v1/admin/user/minilist`, 
            { method: "POST", credentials: "same-origin", body: formData({ userIdList: JSON.stringify(userIds) }) }
        )
        if (response.failed) {
            handleException(`Failed to fetch list of user mini metadata: ${status}`, "Failed to load users with this role.", response.exception)
            return null
        }
        const st = response.code
        const json = response.json()

        if (st.ok) {
            return json
        } else if (st.serverDown) {
            handleError(`Server ${st} while fetching yser mini metadata`, `Server is unavailable.`)
            return null
        } else {
            handleErrorResponse(json, `Failed to load users with this role.`)
            return null
        }
    }

    /**
     * Load list of all filemat users
     */
    async function loadAllUserList() {if (allUsersLoading) return; allUsersLoading = true; try {
        allUsers = null
        const r = await loadUserList()
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
</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


{#if role}
    <div in:fade={{duration: 70}} class="page flex-col gap-8">
        <div class="flex flex-col gap-4 p-6 rounded-lg w-full bg-neutral-200 dark:bg-neutral-800/50">
            <h1 class="text-lg">{role.name}</h1>
            <p class="dark:text-neutral-300">Created on: {formatUnixTimestamp(role.createdDate)}</p>
            {#if appState.systemRoleIds?.admin === role.roleId}
                <p>This is a system role that was created automatically. It has all available permissions.</p>
            {:else if appState.systemRoleIds?.user === role.roleId}
                <p>This is a system role that was created automatically. Every user has this role.</p>
            {/if}
        </div>

        <div class="flex flex-col gap-4">
            {#if role.miniUsers}
                <h2 class="text-lg">Users with this role:</h2>
                {#if role.roleId !== appState.systemRoleIds?.user}
                    <div class="flex gap-3 flex-wrap w-full">
                        {#each sortArrayAlphabetically(role.miniUsers, v => v.username) as mini}
                            <a href="/settings/users/{mini.userId}" title={mini.userId} class="p-3 rounded bg-neutral-200 hover:bg-neutral-300 dark:bg-neutral-900 dark:hover:bg-neutral-800">{mini.username}</a>
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

            <div class="w-full"><hr class="border-neutral-300 dark:border-neutral-700"></div>

            {#if highestRolePermissionLevel && hasPermissionLevel(highestRolePermissionLevel)}
                <button bind:this={addUsersButton} on:click={() => { addUserPopoverOpen = true }} on:click={loadAllUserList} class="w-fit px-4 py-2 rounded bg-neutral-200 hover:bg-neutral-300 dark:bg-neutral-800/80 dark:hover:bg-neutral-700">Add users</button>
            {/if}
            
            {#if addUsersButton}
                <Popover bind:isOpen={addUserPopoverOpen} fadeDuration={40} marginRem={1} button={addUsersButton}>
                    <div class="max-w-full w-[13rem] rounded-md bg-neutral-300 dark:bg-neutral-800 overflow-y-auto overflow-x-hidden max-h-[28rem] min-h-[2rem] h-fit">
                        {#if allUsers}
                            {#if !includesList(role.miniUsers.map(v=>v.userId), allUsers.map(v=>v.userId))}
                                <div in:fade={{duration:40}} class="flex flex-col gap-2 p-2">
                                    {#each sortArrayAlphabetically(allUsers, v => v.username) as user}
                                        {@const hasRole = role.miniUsers.map(v => v.userId).includes(user.userId)}
                                        {#if !hasRole}
                                            <button disabled={addingUser} on:click={() => { addUser(user) }} class="rounded w-full bg-neutral-300 hover:bg-neutral-200 dark:bg-neutral-900 dark:hover:bg-neutral-700 text-left px-2 py-1">{user.username}</button>
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

        <div class="flex flex-col gap-4">
            <h2 class="text-lg">Permissions:</h2>
            <div class="flex gap-3 flex-wrap">
                {#each sortArrayByNumberDesc(role.permissions.map(v => getPermissionInfo(v)), v => v.level) as meta}
                    {#if meta.type !== PermissionType.file}
                        <span title={meta.description} class="px-2 py-1 bg-neutral-300 dark:bg-neutral-700/40 rounded w-fit">
                            {meta.name}
                        </span>
                    {/if}
                {:else}
                    <p class="text-sm dark:text-neutral-300 p-2 rounded bg-neutral-200 dark:bg-neutral-900">No permissions</p>
                {/each}
            </div>
        </div>
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