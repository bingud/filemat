<script lang="ts">
    import { goto } from "$app/navigation";
    import type { SystemPermission, Role } from "$lib/code/auth/types";
    import { systemPermissionMeta } from "$lib/code/data/permissions";
    import { appState } from "$lib/code/stateObjects/appState.svelte";
    import { auth } from "$lib/code/stateObjects/authState.svelte";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import type { ulid } from "$lib/code/types/types";
    import { formData, handleError, handleErrorResponse, handleException, isBlank, pageTitle, removeString, safeFetch, sortArrayByNumberDesc, unixNow, valuesOf } from "$lib/code/util/codeUtil.svelte"
    import { toast } from "@jill64/svelte-toast";
    import { onMount } from "svelte"

    const title = "Create a new role"

    let selectedPermissions: SystemPermission[] = $state([])
    let running = $state(false)

    let nameInput = $state('')

    onMount(() => {
        uiState.settings.title = "New role"
    })

    async function submit() {if (running) return; running = true; try {
        if (isBlank(nameInput)) {
            toast.error(`Role name is empty.`)
            return
        } else if (nameInput.length > 128) {
            toast.error(`Role name is too long.`)
            return
        }

        const permissions = JSON.stringify(selectedPermissions)
        const body = formData({ name: nameInput, permissions: permissions })

        const response = await safeFetch(`/api/v1/admin/role/create`, { body: body })
        if (response.failed) {
            handleException(`Failed to create role: ${response.status}`, `Failed to create new role.`, response.exception)
            return
        }
        const status = response.code
        const json = response.json()

        if (status.ok) {
            const newRoleId = json.roleId as ulid
            const role: Role = {
                roleId: newRoleId,
                name: nameInput,
                createdDate: unixNow(),
                permissions: selectedPermissions
            }

            if (appState.roleList) appState.roleList.push(role)
            await goto(`/settings/roles`)
        } else if (status.serverDown) {
            handleError(`Failed to create role: ${status}`, `Failed to create role.`)
        } else {
            handleErrorResponse(json, `Failed to create role.`)
        }
    } finally { running = false }}

    function selectPermission(id: SystemPermission) {
        if (selectedPermissions.includes(id)) {
            removeString(selectedPermissions, id)
        } else {
            selectedPermissions.push(id)
        }
    }
</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


<div class="page flex-col gap-4">
    <form class="flex flex-col gap-8 w-full" on:submit|preventDefault={submit}>
        <div class="flex flex-col w-[27rem] max-w-full gap-2">
            <label for="name-input">Name</label>
            <input required disabled={running} bind:value={nameInput} id="name-input" minlength="1" maxlength="128">
        </div>

        <div class="flex flex-col w-[35rem] max-w-full gap-2">
            <label for="permissions-input">Permissions</label>
            <div id="permissions-input" class="w-full flex flex-wrap gap-3">
                {#each sortArrayByNumberDesc(valuesOf(systemPermissionMeta), v=>v.level) as permission}
                    {#if auth.permissionLevel && auth.permissionLevel >= permission.level}
                        <button type="button" class="rounded bg-neutral-200 dark:bg-neutral-800 px-2 py-1 hover:text-blue-500 dark:hover:text-blue-400 {selectedPermissions.includes(permission.id) ? 'ring-2 ring-blue-400' : ''}" on:click={()=>{ selectPermission(permission.id) }}>{permission.name}</button>
                    {/if}
                {/each}
            </div>
        </div>

        <button type="submit" class="tw-form-button w-fit">{#if !running}Create{:else}Creating...{/if}</button>
    </form>
</div>


<style>
    @import "/src/app.css" reference;

</style>