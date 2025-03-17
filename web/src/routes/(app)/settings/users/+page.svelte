<script lang="ts">
    import { handleError, handleErrorResponse, isServerDown, pageTitle, parseJson, safeFetch } from "$lib/code/util/codeUtil.svelte";
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
    }

</script>


<svelte:head>
    <title>{pageTitle("User Settings")}</title>
</svelte:head>


<div class="page">
    
</div>