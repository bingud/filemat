<script lang="ts">
    import { page } from "$app/state";
    import type { AccountProperty, FullPublicUser, PublicAccountProperty } from "$lib/code/auth/types";
    import { getMaxPermissionLevel } from "$lib/code/module/permissions";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import type { record, ulid } from "$lib/code/types/types";
    import { entriesOf, explicitEffect, formatUnixTimestamp, formData, handleErr, handleException, includesList, isServerDown, lockFunction, pageTitle, parseJson, removeString, safeFetch, sortArrayAlphabetically, sortArrayByNumber, sortArrayByNumberDesc } from "$lib/code/util/codeUtil.svelte";
    import { getRole } from "$lib/code/util/stateUtils";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import EditIcon from "$lib/component/icons/EditIcon.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import Popover from "$lib/component/popover/CustomPopover.svelte";
    import { fade } from "svelte/transition";
    import { assignRole, changeUserPassword, editUserProperty, loadUser, removeSelectedRoles, resetUserMfa, selectRole, toggleRoleSelection } from "./actions";
    import { userPageState as pageState } from "./state.svelte";
    import { onMount } from "svelte";

    const title = "Manage user"

    const editableFields: record<PublicAccountProperty, string> = {
        "email": "Email",
        "username": "Username",
    }

    const viewableFields: record<PublicAccountProperty, string> = {
        "mfaTotpStatus": "2FA enabled",
        "userId": "User ID",
        "mfaTotpRequired": "Enforce 2FA",
    }

    const viewableDateFields: record<PublicAccountProperty, string> = {
        "createdDate": "Created date",
        "lastLoginDate": "Last login date",
    }

    let loading = $state(true)
    let mounted = $state(false)

    let addRolesButton: undefined | HTMLElement = $state()
    let addRolesDisabled = $derived.by(() => {
        if (!pageState.user || !appState.roleList) return true

        return includesList(pageState.user.roles, appState.roleList.map(v=>v.roleId))
    })

    onMount(() => {
        uiState.settings.title = title

        return () => {
            pageState.pageUserId = null
            pageState.reset()
        }
    })

    explicitEffect(() => [ 
        page.params.userId 
    ], () => {
        const userId = page.params.userId
        pageState.pageUserId = userId || null
        pageState.reset()

        if (!userId || userId.length !== 26) {
            loading = false
            mounted = true
            return
        }

        loading = true
        loadUser(userId).then(() => { loading = false })
        mounted = true
    })
</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


{#if pageState.user}
    {@const user = pageState.user}
    {@const selectingRoles = pageState.selectingRoles}
    {@const userObj = user as any}

    <!-- in:fade={{duration: 70}} -->
    <div class="flex flex-col gap-8">
        <!-- First section -->
        <section class="flex flex-col lg:flex-row gap-8 lg:gap-6">
            <!-- Left side - Editable fields -->
            <div class="detail-panel">
                <h2 class="detail-section-title">User info</h2>
                <div class="details-holder">
                    {#each entriesOf(editableFields) as [field, name]}
                        <div class="detail-card">
                            <p class="detail-label">{name}</p>
                            <div class="h-fit flex gap-2 w-full">
                                <p class="detail-content !w-auto flex-1">{userObj[field]}</p>
                                <button on:click={() => { editUserProperty(user.userId, field, userObj[field]) }} class="basic-button edit-button">
                                    <EditIcon></EditIcon>
                                </button>
                            </div>
                        </div>
                    {/each}
                </div>

                <hr class="basic-hr my-6 mr-[30%]">

                <h2 class="detail-section-title">Roles</h2>
                <div class="flex flex-wrap gap-2 p-2 rounded-lg bg-surface">
                    {#if user?.roles}
                        {#each sortArrayByNumberDesc(user.roles.map(v => getRole(v)).filter(v => v != null), v => getMaxPermissionLevel(v.permissions)) as role}
                            {#if !selectingRoles}
                                <a href="/settings/roles/{role.roleId}" class="detail-content !bg-surface-button !w-fit hover:text-blue-400 hover:underline">{role.name}</a>
                            {:else}
                                <button on:click={()=>{ selectRole(role.roleId) }} class="detail-content !w-fit hover:text-red-400 {pageState.selectedRoles.includes(role.roleId) ? 'ring-2 ring-red-400' : ''}">{role.name}</button>
                            {/if}
                        {/each}
                    {/if}

                    {#if !addRolesDisabled && !selectingRoles}
                        <button bind:this={addRolesButton} id="add-roles" title="Assign a role" class="detail-content aspect-square h-12 !w-auto flex items-center justify-center hover:!bg-blue-400/40 dark:hover:!bg-blue-400/20 disabled:pointer-events-none cursor-pointer">
                            <div class="size-4 rotate-45">
                                <CloseIcon></CloseIcon>
                            </div>
                        </button>
                    {/if}

                    {#if appState.roleList && !addRolesDisabled && addRolesButton}
                        <Popover onClose={() => {}} button={addRolesButton} marginRem={1} fadeDuration={40}>
                            <div class="max-w-full w-[13rem] rounded-md bg-neutral-300 dark:bg-neutral-800 overflow-y-auto overflow-x-hidden max-h-[28rem] min-h-[2rem] h-fit">
                                <div class="flex flex-col gap-2 p-2">
                                    {#each sortArrayByNumberDesc(appState.roleList, v => getMaxPermissionLevel(v.permissions)) as role}
                                        {@const hasRole = user.roles.includes(role.roleId)}
                                        {@const rolePermissionLevel = getMaxPermissionLevel(role.permissions)}
                                        {#if !hasRole && (auth.permissionLevel ?? 0) >= rolePermissionLevel}
                                            <button on:click={() => { assignRole(role.roleId) }} class="rounded hover:bg-neutral-200 dark:bg-neutral-900 dark:hover:bg-neutral-700 text-left px-2 py-1 !w-full">{role.name}</button>
                                        {/if}
                                    {/each}
                                </div>
                            </div>
                        </Popover>
                    {/if}
                </div>

                <div class="flex gap-3 mt-4">
                    <button disabled={pageState.removingRoles} on:click={toggleRoleSelection} class:opacity-60={!selectingRoles} class="size-fit text-sm py-2 px-3 rounded bg-neutral-300 dark:bg-neutral-800/60 hover:opacity-100 hover:bg-neutral-300 dark:hover:bg-neutral-700">
                        {#if !selectingRoles}
                            Select roles to remove
                        {:else}
                            Cancel selection
                        {/if}
                    </button>

                    {#if selectingRoles}
                        <button on:click={removeSelectedRoles} disabled={pageState.selectedRoles.length < 1 || pageState.removingRoles} class="size-fit text-sm py-2 px-3 rounded bg-neutral-300 dark:bg-neutral-800/60 hover:bg-neutral-300 dark:hover:bg-neutral-700 disabled:opacity-60">
                            {#if !pageState.removingRoles}
                                Removes roles
                            {:else}
                                Removing...
                            {/if}
                        </button>
                    {/if}
                </div>
            </div>

            <!-- Right side - Read-only data -->
            <div class="detail-panel">
                <h2 class="detail-section-title">Details</h2>
                <div class="details-holder">
                    {#each entriesOf(viewableDateFields) as [field, name]}
                        <div class="detail-card">
                            <p class="detail-label">{name}</p>
                            <p class="detail-content">{formatUnixTimestamp(user[field] as number)}</p>
                        </div>
                    {/each}
                    {#each entriesOf(viewableFields) as [field, name]}
                        <div class="detail-card">
                            <p class="detail-label">{name}</p>
                            <p class="detail-content">{user[field]}</p>
                        </div>
                    {/each}
                </div>
            </div>
        </section>

        <hr class="basic-hr">

        <!-- Second section -->
        <section class="flex gap-4">
            <button on:click={() => { changeUserPassword(pageState.user) }} class="basic-button">Change password</button>
            <button on:click={() => { resetUserMfa(pageState.user) }} class="basic-button">Reset 2FA</button>
        </section>
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


<style lang="postcss">
    @import "/src/app.css" reference;

    .detail-card {
        @apply w-full md:w-auto md:flex-1 max-w-[30rem];
    }
    .detail-content {
        @apply h-fit p-3 rounded bg-surface w-full whitespace-pre-wrap break-all;
    }
    .details-holder {
        @apply flex flex-col gap-4 w-full;
    }
    .detail-label {
        @apply whitespace-nowrap;
    }
    .detail-section-title {
        @apply text-lg font-medium;
    }
    .detail-panel {
        @apply flex flex-col gap-4 flex-1;
    }
    .edit-button {
        @apply aspect-square !h-12 max-h-full flex items-center justify-center !p-3;
    }
</style>