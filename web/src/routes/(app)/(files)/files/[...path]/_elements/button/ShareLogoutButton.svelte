<script lang="ts">
    import { filesState } from "$lib/code/stateObjects/filesState.svelte"
    import LogoutIcon from "$lib/component/icons/LogoutIcon.svelte"

    function logout() {
        if (filesState.meta.type !== `shared`) return
        filesState.meta.onLogout?.()
    }
</script>

<button title="Log out of this shared file" onclick={logout} class="file-action-button">
    <LogoutIcon />
</button>
