<script lang="ts">
    import { goto } from "$app/navigation";
    import type { PublicUser } from "$lib/code/auth/types";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { formatUnixTimestamp, handleError, handleErrorResponse, isServerDown, pageTitle, parseJson, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte";
    import { fade } from "svelte/transition";

    const title = "Users"
    let users: PublicUser[] | null = $state(null)
    let loading = $state(false)

    onMount(() => {
        uiState.settings.title = title
        loadUserList()
    })


    async function loadUserList() {
        if (loading) return; loading = true; try {

        const response = await safeFetch(`/api/v1/admin/user/list`, { method: "POST", credentials: "same-origin" })
        if (response.failed) {
            handleError(response.exception, `Failed to load list of users.`)
            users = null
            return
        }
        const text = await response.text()
        const status = response.status
        const json = parseJson(text)

        if (status === 200) {
            if (json) {
                users = json
            }
        } else if (isServerDown(status)) {
            handleError(`Server ${status} when fetching user list.`, "The server is unavailable.")
        } else {
            handleErrorResponse(json, `Failed to load the list of all users. (${status})`)
        }
    } finally { loading = false }}

</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


<div class="page">
    {#if users}
        <div in:fade={{duration: 70}} class="w-full overflow-y-auto scrollbar h-fit pb-1">
            <table class="w-full max-w-fit">
                <thead>
                    <tr class="dark:bg-neutral-900 text-left">
                        <th class="py-2 px-4">Email</th>
                        <th class="py-2 px-4">Username</th>
                        <th class="py-2 px-4">MFA Status</th>
                        <th class="py-2 px-4">Created Date</th>
                        <th class="py-2 px-4">Last Login</th>
                        <th class="py-2 px-4">Banned</th>
                        <th class="py-2 px-4">User ID</th>
                    </tr>
                </thead>
                <tbody>
                    {#each users as user, index}
                        <tr on:click={() => { goto(`/settings/users/${user.userId}`) }} class="{index % 2 === 0 ? 'bg-neutral-200 dark:bg-neutral-800' : 'bg-neutral-100 dark:bg-neutral-900'} whitespace-nowrap">
                            <td>
                                <a href="/settings/users/{user.userId}" class="py-2 px-4 hover:text-blue-400 hover:underline">{user.email}</a>
                            </td>
                            <td class="py-2 px-4">{user.username}</td>
                            <td class="py-2 px-4">{user.mfaTotpStatus ? 'Enabled' : 'Disabled'}</td>
                            <td class="py-2 px-4">{formatUnixTimestamp(user.createdDate)}</td>
                            <td class="py-2 px-4">
                                {user.lastLoginDate 
                                    ? formatUnixTimestamp(user.lastLoginDate) 
                                    : 'N/A'}
                            </td>
                            <td class="py-2 px-4">{user.isBanned ? 'Yes' : 'No'}</td>
                            <td class="py-2 px-4">
                                <a title="Open admin page for this user" href="/settings/users/{user.userId}" class="text-blue-400 hover:underline">{user.userId}</a>
                            </td>
                        </tr>
                    {/each}
                </tbody>
            </table>
        </div>
    {:else if loading}
    <div in:fade={{duration: 200}} class="size-full flex items-center justify-center">
            <Loader />
        </div>
    {:else}
        <div class="p-6 bg-neutral-800 rounded">
            <p>Failed to load users.</p>
        </div>
    {/if}
</div>