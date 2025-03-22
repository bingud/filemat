<script lang="ts">
    import { dev } from "$app/environment";
    import { page } from "$app/state";
    import type { FullPublicUser } from "$lib/code/auth/types";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import type { ulid } from "$lib/code/types";
    import { delay, formatUnixTimestamp, formData, handleError, handleErrorResponse, handleException, includesList, isServerDown, lockFunction, pageTitle, parseJson, removeString, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { getRole } from "$lib/code/util/stateUtils";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import Popover from "$lib/component/Popover.svelte";
    import { fade } from "svelte/transition";

    const title = "Manage user"
    let user: FullPublicUser | null = $state(null)
    let loading = $state(true)
    let mounted = $state(false)

    let selectingRoles = $state(false)
    let selectedRoles: ulid[] = $state([])

    let addRolesDisabled = $derived.by(() => {
        if (!user || !auth.roleList) return true

        return includesList(user.roles, auth.roleList.map(v=>v.roleId))
    })

    $effect(() => {
        uiState.settings.title = title

        const userId = page.params.userId
        if (!userId || userId.length !== 26) {
            user = null
            loading = false
            mounted = true
            return
        }

        loading = true
        loadUser(userId).then(() => { loading = false })
        mounted = true
    })

    /**
     * Load user data
     */
    async function loadUser(userId: ulid) {
        const body = formData({ userId: userId })
        const response = await safeFetch(`/api/v1/admin/user/get`, { method: "POST", body: body, credentials: "same-origin" })
        if (response.failed) {
            handleException(`Failed to fetch user by user id`, "Failed to load user data.", response.exception)
            return
        }
        const status = response.code
        const json = response.json()

        if (status.ok) {
            user = json
        } else if (status.serverDown) {
            handleError(`Failed to fetch user data. server ${status}`, `Failed to load user data. Server is unavailable.`)
        } else {
            handleErrorResponse(json, "Failed to load user data.")
            user = null
        }
    }

    /**
     * Assign role to user
     */
    const assignRole = lockFunction(async (roleId: ulid) => {
        if (!user) return

        const body = formData({ userId: user.userId, roleId: roleId })
        const response = await safeFetch(`/api/v1/admin/user-role/assign`, { body: body })
        if (response.failed) {
            handleException(`Failed to assign role to user.`, `Failed to assign role.`, response.exception)
            return
        }
        const status = response.code
        const json = response.json()

        if (status.ok) {
            if (user) {
                user.roles.push(roleId)

                if (auth.principal!.userId === user.userId) {
                    auth.principal!.roles.push(roleId)
                }
            }
        } else if (status.serverDown) {
            handleError(`Server ${status} when assigning role`, `Server is unavilable.`)
        } else {
            handleErrorResponse(json, `Failed to assign role.`)
        }
    })

    /**
     * Role selection
     */
    function toggleRoleSelection() {
        selectedRoles = []
        selectingRoles = !selectingRoles
    }

    function selectRole(id: ulid) {
        if (!selectedRoles.includes(id)) {
            selectedRoles.push(id)
        } else {
            removeString(selectedRoles, id)
        }
    }

    let removingRoles = $state(false)
    async function removeSelectedRoles() {
        finish
        if (removingRoles) return; removingRoles = true
        try {
            if (selectedRoles.length < 1 || !selectingRoles || !user) return

            const userId = user.userId
            const body = formData({ userId: userId, roleIdList: JSON.stringify(selectedRoles) })
            const response = await safeFetch(`/api/v1/admin/user-role/remove`, { body: body })
            if (response.failed) {
                handleException(`Failed to remove selected roles from user`, `Failed to remove selected roles.`, response.exception)
                return
            }
            const json = response.json()
            const status = response.code

            if (status.ok) {
                if (user && user.userId === userId) {
                    selectedRoles.forEach((roleId) => {  
                        removeString(user!.roles, roleId)
                    })
                }

                toggleRoleSelection()
            } else if (status.serverDown) {
                handleError(`Server ${status} when removing selected roles`, `Failed to remove selected roles. Server is unavailable.`)
            } else {
                handleErrorResponse(json, `Failed to remove selected roles.`)
            }
        } finally {
            removingRoles = false
        }
    }
</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


{#if user}
    <div in:fade={{duration: 70}} class="flex flex-col gap-2">
        <h2 class="detail-section-title">User info</h2>
        <div class="details-holder">
            <div class="detail-card">
                <p class="detail-label">Username</p>
                <p class="detail-content">{user.username}</p>
            </div>

            <div class="detail-card">
                <p class="detail-label">Email</p>
                <p class="detail-content">{user.email}</p>
            </div>

            <div class="detail-card">
                <p class="detail-label">2FA</p>
                <p class="detail-content">{user.mfaTotpStatus ? "Enabled" : "Disabled"}</p>
            </div>
        </div>

        <hr class="detail-hr">

        <h2 class="detail-section-title">Roles</h2>
        <div class="details-holder">
            {#each user.roles as roleId}
                {@const role = getRole(roleId)}
                {#if role}
                    {#if !selectingRoles}
                        <a href="/settings/roles/{roleId}" class="detail-content !w-fit hover:text-blue-400 hover:underline">{role.name}</a>
                    {:else}
                        <button on:click={()=>{ selectRole(role.roleId) }} class="detail-content !w-fit hover:text-blue-400 hover:underline {selectedRoles.includes(role.roleId) ? 'ring-2 ring-blue-400' : ''}">{role.name}</button>
                    {/if}
                {:else}
                    <div class="detail-card">
                        <p class="detail-label">Invalid role</p>
                        <p class="detail-content text-red-300">{roleId}</p>
                    </div>
                {/if}
            {/each}

            {#if !addRolesDisabled}
                <button id="add-roles" title="Assign a role" class="detail-content aspect-square h-12 !w-auto flex items-center justify-center hover:!bg-blue-400/40 dark:hover:!bg-blue-400/20 disabled:pointer-events-none">
                    <div class="size-4 rotate-45">
                        <CloseIcon></CloseIcon>
                    </div>
                </button>
            {/if}

            {#if auth.roleList && !addRolesDisabled}
                <Popover buttonId="add-roles" marginRem={1} fadeDuration={40} open={dev}>
                    <div class="max-w-full w-[13rem] rounded-md bg-neutral-800 overflow-y-auto overflow-x-hidden max-h-[28rem] min-h-[2rem] h-fit">
                        <div class="flex flex-col gap-2 p-2">
                            {#each auth.roleList as role}
                                {@const hasRole = user.roles.includes(role.roleId)}
                                {#if !hasRole}
                                    <button on:click={() => { assignRole(role.roleId) }} class="rounded w-full bg-neutral-900 text-left px-2 py-1 hover:bg-neutral-700">{role.name}</button>
                                {/if}
                            {/each}
                        </div>
                    </div>
                </Popover>
            {/if}
        </div>
        <!-- Role selection buttons -->
        <div class="flex gap-3 mt-4">
            <button disabled={removingRoles} on:click={toggleRoleSelection} class:opacity-60={!selectingRoles} class="size-fit text-sm py-2 px-3 rounded bg-neutral-300 dark:bg-neutral-800/60 hover:opacity-100 hover:bg-neutral-300 dark:hover:bg-neutral-700">
                {#if !selectingRoles}
                    Select roles to remove
                {:else}
                    Cancel selection
                {/if}
            </button>

            {#if selectingRoles}
                <button on:click={removeSelectedRoles} disabled={selectedRoles.length < 1 || removingRoles} class="size-fit text-sm py-2 px-3 rounded bg-neutral-300 dark:bg-neutral-800/60 hover:bg-neutral-300 dark:hover:bg-neutral-700 disabled:opacity-60">
                    {#if !removingRoles}
                        Removes roles
                    {:else}
                        Removing...
                    {/if}
                </button>
            {/if}
        </div>

        <hr class="detail-hr">

        <h2 class="detail-section-title">Details</h2>
        <div class="details-holder">
            <div class="detail-card">
                <p class="detail-label">Is banned</p>
                <p class="detail-content">{user.isBanned ? "Yes" : "No"}</p>
            </div>

            <div class="detail-card">
                <p class="detail-label">Created at</p>
                <p class="detail-content">{formatUnixTimestamp(user.createdDate)}</p>
            </div>

            <div class="detail-card">
                <p class="detail-label">Last login at</p>
                <p class="detail-content">{user.createdDate ? formatUnixTimestamp(user.createdDate) : "N/A"}</p>
            </div>

            <div class="detail-card">
                <p class="detail-label">User ID</p>
                <p class="detail-content">{user.userId}</p>
            </div>
        </div>
    </div>
{:else if loading}
    <div in:fade={{ duration: 200 }} class="size-full flex items-center justify-center">
        <Loader></Loader>
    </div>
{:else if mounted}
    <div class="m-auto">
        <p class="text-xl">No data</p>
    </div>
{/if}


<style>
    @import "/src/app.css" reference;

    .detail-card {
        @apply w-full md:w-auto md:flex-1 max-w-[30rem];
    }
    .detail-content {
        @apply p-3 rounded bg-neutral-200 dark:bg-neutral-900 w-full whitespace-pre-wrap break-all md:whitespace-nowrap;
    }
    .details-holder {
        @apply flex flex-wrap gap-4 w-full;
    }
    .detail-label {
        @apply whitespace-nowrap;
    }
    .detail-hr {
        @apply border-neutral-300 dark:border-neutral-800 my-6;
    }
    .detail-section-title {
        @apply text-lg font-medium;
    }
</style>