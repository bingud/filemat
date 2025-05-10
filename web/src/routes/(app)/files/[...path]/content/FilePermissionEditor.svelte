<script lang="ts">
    import type { FilePermission } from "$lib/code/auth/types";
    import { filePermissionMeta } from "$lib/code/data/permissions";
    import type { ulid } from "$lib/code/types/types";
    import { filterObject, formData, handleError, handleErrorResponse, handleException, keysOf, mapToObject, safeFetch, valuesOf } from "$lib/code/util/codeUtil.svelte";
    import RoleIcon from "$lib/component/icons/RoleIcon.svelte";
    import UserIcon from "$lib/component/icons/UserIcon.svelte";
    import type { EntityPermissionMeta } from "./code/types";


    let { editedPermission, onPermissionUpdated }: {
        editedPermission: EntityPermissionMeta,
        onPermissionUpdated: (id: ulid, newPermissions: FilePermission[] | null, deleted: boolean) => any
    } = $props()

    const username = editedPermission.username
    const role = editedPermission.role
    const perm = editedPermission.permission

    const allPermissions = valuesOf(filePermissionMeta)
    let selectedPermissions = $state(mapToObject(allPermissions, (v) => { 
        return { key: v.id, value: perm.permissions.includes(v.id) }
    }))

    let loading = $state(false)
    let deleting = $state(false)

    async function editPermission() {
        if (loading) return
        loading = true

        try {
            const permissionList = keysOf(filterObject(selectedPermissions, (k, v) => v))
            const response = await safeFetch(`/api/v1/permission/update-entity`, { body: formData({ permissionId: perm.permissionId, newPermissionList: JSON.stringify(permissionList) }) })
            if (response.failed) {  
                handleException(`Failed to update file permission`, `Failed to update permission.`, response.exception)
                return
            }
            const status = response.code
            const json = await response.json()

            if (status.ok) {
                onPermissionUpdated(perm.permissionId, permissionList, false)
            } else if (status.serverDown) {
                handleError(`Server ${status} when updating file permission`, `Failed to update permission. Server is unavailable.`)
                return
            } else {
                handleErrorResponse(json, `Failed to update permission.`)
                return
            }
        } finally {
            loading = false
        }
    }

    async function deletePermission() {
        if (deleting) return
        deleting = true

        try {
            const response = await safeFetch(`/api/v1/permission/delete-entity`, { body: formData({ permissionId: perm.permissionId }) })
            if (response.failed) {  
                handleException(`Failed to delete file permission`, `Failed to delete permission.`, response.exception)
                return
            }
            const status = response.code
            const json = await response.json()

            if (status.ok) {
                onPermissionUpdated(perm.permissionId, null, true)
            } else if (status.serverDown) {
                handleError(`Server ${status} when deleting file permission`, `Failed to delete permission. Server is unavailable.`)
                return
            } else {
                handleErrorResponse(json, `Failed to delete permission.`)
                return
            }
        } finally {
            deleting = false
        }
    }

</script>


<div class="size-full max-h-[80svh] flex flex-col gap-4">
    <div class="p-4 rounded-lg w-full bg-neutral-200 dark:bg-neutral-800 flex items-center gap-3">
        <div class="aspect-square h-[1.2rem]">
            {#if perm.permissionType === "ROLE"}
                <RoleIcon></RoleIcon>
            {:else}
                <UserIcon></UserIcon>
            {/if}
        </div>
        <p>{username ?? role!.name}</p>
    </div>

    <hr class="basic-hr">

    <p>Permissions:</p>
    <div class="flex flex-col gap-2">
        {#each valuesOf(filePermissionMeta) as permission}
            {@const id = `input-permission-${permission.id}`}
            <div class="flex gap-2 items-center">
                <input bind:checked={selectedPermissions[permission.id]} id={id} type="checkbox">
                <label for={id}>{permission.name}</label>
            </div>
        {/each}
    </div>

    <button disabled={loading} on:click={editPermission} class="w-full rounded-lg py-2 bg-neutral-300 dark:bg-neutral-700 hover:bg-neutral-400 dark:hover:bg-neutral-600 disabled:opacity-50">{#if !loading}Update permission{:else}Creating...{/if}</button>

    <hr class="basic-hr">
    
    <button disabled={deleting} on:click={deletePermission} class="w-full rounded-lg py-2 bg-neutral-300 dark:bg-neutral-700 dark:hover:bg-neutral-600 hover:ring-2 hover:ring-red-500 disabled:opacity-50">{#if !deleting}Delete permission{:else}Deleting...{/if}</button>
</div>