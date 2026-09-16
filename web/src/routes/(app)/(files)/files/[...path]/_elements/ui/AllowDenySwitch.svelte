<script lang="ts">
    import CheckmarkIcon from "$lib/component/icons/CheckmarkIcon.svelte"
    import CloseIcon from "$lib/component/icons/CloseIcon.svelte"

    let {
        value,
        onchange,
        disabled = false,
    }: {
        value: boolean | null
        onchange: (allowed: boolean) => void
        disabled?: boolean
    } = $props()
</script>

<div
    class="inline-flex h-9 rounded-lg overflow-hidden bg-neutral-200 dark:bg-neutral-800"
    role="group"
>
    <button
        type="button"
        {disabled}
        aria-label="Allow"
        aria-pressed={value === true}
        class="h-full w-10 flex items-center justify-center transition-colors disabled:opacity-40 disabled:cursor-not-allowed {value === true ? `bg-green-500 fill-white` : `opacity-40 hover:opacity-70 fill-current`}"
        onclick={() => onchange(true)}
    >
        <div class="size-4">
            <CheckmarkIcon />
        </div>
    </button>
    <button
        type="button"
        {disabled}
        aria-label="Deny"
        aria-pressed={value === false}
        class="h-full w-10 flex items-center justify-center transition-colors disabled:opacity-40 disabled:cursor-not-allowed {value === false ? `bg-red-500 fill-white` : `opacity-40 hover:opacity-70 fill-current`}"
        onclick={() => onchange(false)}
    >
        <div class="size-4">
            <CloseIcon />
        </div>
    </button>
</div>
