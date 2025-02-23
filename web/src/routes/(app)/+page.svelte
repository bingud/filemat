<script lang="ts">
    import { auth } from "$lib/code/auth/auth.svelte";


</script>


<div class="size-full flex flex-col items-center justify-center">
    <p>{auth.roleList?.toString()}</p>
    <p>{auth.principal?.roles}</p>
    <p>{auth.principal?.userId}</p>
    <a href="/login">ligni</a>
</div>