<script lang="ts">
    import { page } from "$app/state";
    import { fetchState } from "$lib/code/state/stateFetcher";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { formatUnixTimestamp, pageTitle } from "$lib/code/util/codeUtil.svelte";
    import Loader from "$lib/component/Loader.svelte";
    import { onMount } from "svelte";
    import { fade } from "svelte/transition";

    const title = "Roles"
    let loading: boolean | null = $state(null)

    onMount(() => {
        uiState.settings.title = title

        if (appState.firstPath !== page.url.pathname) {
            loading = true
            fetchState({ roles: true, systemRoleIds: false, principal: false, app: false })
                .then(() => { loading = false })
        }
    })

</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


<div class="page">
    {#if auth.roleList}
        <div in:fade={{duration: 70}} class="w-full overflow-y-auto scrollbar h-fit pb-1">
            <div class="flex flex-col gap-4">
                {#each auth.roleList as role}
                    <div class="p-4 rounded-lg bg-neutral-200 dark:bg-neutral-800/50">
                        <!-- Role Name -->
                        <a href="/settings/roles/{role.roleId}" class="font-medium text-lg mb-2 text-blue-400 hover:underline">
                            {role.name}
                        </a>
                
                        <!-- Created At -->
                        <div class="mb-2">
                            Created at: {formatUnixTimestamp(role.createdDate)}
                        </div>
                
                        <!-- Role ID -->
                        <div class="mb-2">
                            Role ID:
                            <a href="/settings/roles/{role.roleId}" class="hover:text-blue-400 hover:underline" title="Open page to manage this role">
                                {role.roleId}
                            </a>
                        </div>
                
                        <!-- Permissions (wrapped) -->
                        <div class="flex flex-wrap gap-2">
                            {#each role.permissions as permission}
                                <span class="px-2 py-1 bg-neutral-700/40 rounded text-xs">
                                    {permission}
                                </span>
                            {/each}
                        </div>
                    </div>
                {/each}
            </div>
              
        </div>
    {:else if loading !== false}
        <div in:fade={{duration: 200}} class="size-full flex items-center justify-center">
            <Loader></Loader>
        </div>
    {:else}
        <div class="p-6 bg-neutral-800 rounded">
            <p>Failed to load roles.</p>
        </div>
    {/if}
</div>


<style>
    @import "/src/app.css" reference;

</style>