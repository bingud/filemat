<script lang="ts">
    import { page } from "$app/state";
    import type { Role } from "$lib/code/auth/types";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import type { ulid } from "$lib/code/types";
    import { formData, handleError, handleErrorResponse, handleException, pageTitle, parseJson, safeFetch, toStatus } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { onMount } from "svelte";

    type RoleMeta = Role & { users: ulid[] }

    const title = "Manage role"
    let role: RoleMeta | null = $state(null)

    onMount(() => {
        uiState.settings.title = title
    })

    $effect(() => {
        const roleId = page.params.roleId
        if (!Validator.isUlidValid(roleId)) {
            role = null
            return
        }

        loadRoleData(roleId)
    })

    async function loadRoleData(id: string) {
        const response = await safeFetch(`/api/v1/admin/role/get`, { method: "POST", credentials: "same-origin", body: formData({ roleId: id }) })
        if (response.failed) {
            role = null
            handleException(`Failed to fetch role data.`, `Failed to load role.`, response.exception)
            return
        }
        const status = toStatus(response.status)
        const text = await response.text()
        const json = parseJson(text)

        if (status.ok) {
            role = json
        } else if (status.serverDown) {
            handleError(`Server ${status} while fetching role data`, `Server is unavailable.`)
            role = null
        } else {
            handleErrorResponse(json, `Failed to load role.`)
            role = null
        }
    }
</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


<div class="page">
    {#if role}
        <p>{JSON.stringify(role)}</p>
    {/if}
</div>


<style>
    @import "/src/app.css" reference;

</style>