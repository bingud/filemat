<script lang="ts">
    import { Select } from "./bits-ui-wrapper"

    let { 
        value = $bindable(), 
        items, 
        placeholder = "Select an option",
        buttonClasses = "",
        dropdownClasses = "",
        itemClasses = "",
        bgColor = "bg-bg",
        onSelect,
    }: {
        value: string, 
        items: {[key: string]: string}, 
        placeholder?: string,
        buttonClasses?: string,
        dropdownClasses?: string,
        itemClasses?: string,
        bgColor?: string,
        onSelect?: (value: any) => any,
    } = $props()

    const selectedLabel = $derived(
        Object.entries(items).find(([k]) => k === value)?.[1] ?? placeholder
    )
</script>

<Select.Root 
    bind:value 
    type="single" 
    onValueChange={(v) => {
        if (onSelect) onSelect(v)
    }}
>
    <Select.Trigger 
        class="flex items-center justify-between p-2 rounded-md border-none outline-none {buttonClasses} {bgColor ? bgColor : "bg-bg"}"
    >
        {selectedLabel}
    </Select.Trigger>
    <Select.Content 
        class="rounded-md {bgColor ? bgColor : "bg-bg"} shadow-md w-[var(--bits-select-anchor-width)] {dropdownClasses}"
        sideOffset={8}
    >
        {#each Object.entries(items) as [val, label]}
            <Select.Item
                value={val}
                label={label as string}
                class="relative flex w-full cursor-default select-none items-center py-1.5 px-2 text-sm outline-none data-[highlighted]:bg-muted {itemClasses}"
            >
                {label}
            </Select.Item>
        {/each}
    </Select.Content>
</Select.Root>