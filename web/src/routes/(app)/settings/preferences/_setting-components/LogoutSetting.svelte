<script lang="ts">
    import { confirmDialogState } from "$lib/code/stateObjects/subState/utilStates.svelte";
    import { safeFetch } from "$lib/code/util/codeUtil.svelte";
    import { toast } from "@jill64/svelte-toast";


    async function logout() {
        const confirmation = await confirmDialogState.show({ title: `Log out`, message: `Do you want to log out?` })
        if (!confirmation) return

        const response = await safeFetch(`/api/v1/auth/logout`)
        const code = response.code
        if (code.ok) {
            window.location.href = "/login"
            localStorage.clear()
        } else if (code.failed) {
            const json = response.json()
            const error = json.message
            toast.error(error)
        }
    }

</script>


<button on:click={logout} class="basic-button hover:ring-2 hover:ring-red-400">
    Log out
</button>