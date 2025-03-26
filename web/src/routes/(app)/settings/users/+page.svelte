<script lang="ts">
    import { goto } from "$app/navigation";
    
    import { loadUserList } from "$lib/code/admin/users";
    import type { PublicUser } from "$lib/code/auth/types";
    import { hasPermissionLevel } from "$lib/code/data/permissions";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { formatUnixTimestamp, handleError, handleErrorResponse, isServerDown, pageTitle, parseJson, safeFetch, sortArrayByNumber } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte";
    import { fade } from "svelte/transition";

    const title = "Users"
    let users: PublicUser[] | null = $state(null)
    let loading = $state(false)

    onMount(() => {
        uiState.settings.title = title
        if (!hasPermissionLevel(3)) {
            goto(`/settings`)
            return
        }
        loadList()
    })


    async function loadList() {if (loading) return; loading = true; try {
        const r = await loadUserList()
        if (r) {
            users = r
        }
    } finally { loading = false }}

</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


<div class="page flex-col gap-8">
    {#if users}
        <a href="/settings/users/new" class="rounded bg-neutral-200 hover:bg-neutral-300 dark:bg-neutral-800/50 dark:hover:bg-neutral-800 px-3 py-2 w-fit dark:hover:text-blue-400">Create a new user</a>

        <div in:fade={{duration: 70}} class="w-full overflow-y-auto custom-scrollbar h-fit pb-1">
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
                    {#each sortArrayByNumber(users, v => v.createdDate) as user, index}
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
        <div class="p-6 bg-neutral-300 dark:bg-neutral-800 rounded size-fit">
            <p>Failed to load users.</p>
        </div>
    {/if}
</div>