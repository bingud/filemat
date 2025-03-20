<script lang="ts">
    import { page } from "$app/state";
    import type { FullPublicUser, PublicUser } from "$lib/code/auth/types";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import type { ulid } from "$lib/code/types";
    import { formatUnixTimestamp, formData, handleError, handleErrorResponse, handleException, isServerDown, pageTitle, parseJson, safeFetch, valuesOf } from "$lib/code/util/codeUtil.svelte";
    import { getRole } from "$lib/code/util/stateUtils";
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte";
    import { fade } from "svelte/transition";

    const title = "Manage user"
    let user: FullPublicUser | null = $state(null)
    let loading = $state(true)
    let mounted = $state(false)

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

    async function loadUser(userId: ulid) {
        const body = formData({ userId: userId })
        const response = await safeFetch(`/api/v1/admin/user/get`, { method: "POST", body: body, credentials: "same-origin" })
        if (response.failed) {
            handleException(`Failed to fetch user by user id`, "Failed to load user data.", response.exception)
            return
        }
        const status = response.status
        const text = await response.text()
        const json = parseJson(text)

        if (status === 200) {
            user = json
        } else if (isServerDown(status)) {
            handleError(`Failed to fetch user data. server ${status}`, `Failed to load user data.`)
        } else {
            handleErrorResponse(json, "Failed to load user data.")
            user = null
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
                    <a href="/settings/roles/{roleId}" class="detail-content !w-fit hover:text-blue-400 hover:underline">{role.name}</a>
                {:else}
                    <div class="detail-card">
                        <p class="detail-label">Invalid role</p>
                        <p class="detail-content text-red-300">{roleId}</p>
                    </div>
                {/if}
            {/each}
            <button class="detail-content aspect-square h-12 !w-auto flex items-center justify-center hover:!bg-blue-400/40 dark:hover:!bg-blue-400/20">
                <div class="size-4 rotate-45">
                    <CloseIcon></CloseIcon>
                </div>
            </button>
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
        @apply border-neutral-800 my-6;
    }
    .detail-section-title {
        @apply text-lg font-medium;
    }
</style>