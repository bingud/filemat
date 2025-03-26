<script lang="ts">
    import { goto } from "$app/navigation";
    import { createUser } from "$lib/code/admin/users";
    import { uiState } from "$lib/code/stateObjects/uiState.svelte";
    import { pageTitle } from "$lib/code/util/codeUtil.svelte";
    import { Validator } from "$lib/code/util/validation";
    import { toast } from "@jill64/svelte-toast";
    import { onMount } from "svelte";

    const title = "Create a new user"

    let creatingUser = $state(false)
    let emailInput = $state('')
    let usernameInput = $state('')
    let passwordInput = $state('')

    onMount(() => {
        uiState.settings.title = "Create a new user"
    })

    async function create() {if (creatingUser) return; creatingUser = true; try {
        const validation = Validator.email(emailInput) ?? Validator.username(usernameInput) ?? Validator.password(passwordInput)
        if (validation != null) {            
            toast.error(validation)
            return
        }

        const result = await createUser(emailInput, usernameInput, passwordInput)
        if (!result) return
        await goto(`/settings/users/${result}`)
    } finally { creatingUser = false }}

</script>


<svelte:head>
    <title>{pageTitle(title)}</title>
</svelte:head>


<div class="page flex-col">
    <form on:submit|preventDefault={create} class="flex flex-col gap-6 w-full md:max-w-[25rem]">
        <div class="flex flex-col gap-1">
            <label for="input-email">Email</label>
            <input bind:value={emailInput} id="input-email" type="email" maxlength="256" required>
        </div>

        <div class="flex flex-col gap-1">
            <label for="input-username">Username</label>
            <input bind:value={usernameInput} id="input-username" minlength="1" maxlength="48" required>
        </div>

        <div class="flex flex-col gap-1">
            <label for="input-password">Password</label>
            <input bind:value={passwordInput} id="input-password" type="password" minlength="4" maxlength="256" required>
        </div>

        <button class="tw-form-button w-fit">Create user</button>
    </form>
</div>


<style>
    @import "/src/app.css" reference;

</style>