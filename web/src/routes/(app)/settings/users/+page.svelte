<script lang="ts">
    import { handleError, handleErrorResponse, pageTitle, safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { onMount } from "svelte";

    let users = $state(null)

    onMount(() => {
        loadUserList()
    })


    async function loadUserList() {
        const response = await safeFetch(`/api/v1/admin/user/list`, { method: "POST", credentials: "same-origin" })
        if (response.failed) {
            handleError(response.exception, `Failed to load list of users.`)
            users = null
            return
        }
    }

</script>


<svelte:head>
    <title>{pageTitle("User Settings")}</title>
</svelte:head>


<div class="page">
    
</div>