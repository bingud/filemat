<script lang="ts">
    import { safeFetch } from "$lib/code/util/codeUtil.svelte";


    async function logout() {
        const response = await safeFetch(`/api/auth/logout`)

        
    }

</script>


<button on:click={logout} class="basic-button hover:ring-2 hover:ring-red-400">
    Log out
</button>