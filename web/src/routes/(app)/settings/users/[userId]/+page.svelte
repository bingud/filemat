<script lang="ts">
    import { page } from "$app/state";
    import type { FullPublicUser, PublicUser } from "$lib/code/auth/types";
    import type { ulid } from "$lib/code/types";
    import { formatUnixTimestamp, formData, handleError, handleErrorResponse, handleException, isServerDown, pageTitle, parseJson, safeFetch, valuesOf } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";


    let user: FullPublicUser | null = $state(null)
    let loading = $state(false)

    $effect(() => {
        const userId = page.params.userId
        if (!userId || userId.length !== 26) {
            user = null
            return
        }
        loadUser(userId)
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
    <title>{pageTitle("Manage user")}</title>
</svelte:head>


<div class="flex flex-col w-full">
    {#if user}
        <div class="flex flex-col md:flex-row w-full">
            <!-- Main user metadata -->
            <div class="flex flex-col gap-2">
                <div class="user-item">
                    <p class="opacity-80 text-sm">Username:</p>
                    <p class="">{user.username}</p>
                </div>

                <div class="user-item">
                    <p class="opacity-80 text-sm">Email:</p>
                    <p class="">{user.email}</p>
                </div>

                <div class="user-item">
                    <p class="opacity-80 text-sm">TOTP 2FA:</p>
                    <p class="">{user.mfaTotpStatus ? "Enabled" : "Disabled"}</p>
                </div>

                <div class="user-item">
                    <p class="opacity-80 text-sm">Account created:</p>
                    <p class="">{formatUnixTimestamp(user.createdDate)}</p>
                </div>

                <div class="user-item">
                    <p class="opacity-80 text-sm">Last login:</p>
                    <p class="">{user.lastLoginDate ? formatUnixTimestamp(user.lastLoginDate) : "N/A"}</p>
                </div>

                <div class="user-item">
                    <p class="opacity-80 text-sm">Is banned:</p>
                    <p class="">{user.isBanned ? "Yes" : "No"}</p>
                </div>

                <div class="user-item">
                    <p class="opacity-80 text-sm">User ID:</p>
                    <p class="">{user.userId}</p>
                </div>
            </div>

            <!-- Users Roles -->
            <div class="flex flex-col">
                {#each user.roles as roleId}
                    <p>{roleId}</p>
                {/each}
            </div>
        </div>
    {/if}
</div>


<style>
    .user-item {
        @apply flex flex-col p-4 rounded bg-neutral-900;
    }
</style>