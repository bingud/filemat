<script lang="ts">
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import CheckmarkIcon from "$lib/component/icons/CheckmarkIcon.svelte";
    import type { LogType } from "./logs";

    const types: LogType[] = ["SYSTEM", "SECURITY", "AUTH", "AUDIT"]

    let {
        selectedTypes = $bindable(),
        open = $bindable(),
    }: {
        selectedTypes: LogType[],
        open: boolean
    } = $props()

    function toggleType(type: LogType) {
        if (selectedTypes.includes(type)) {
            selectedTypes = selectedTypes.filter(s => s !== type);
        } else {
            selectedTypes = [...selectedTypes, type];
        }
    }

    function selectAll() {
        selectedTypes = [...types]
    }
</script>

<Popover.Root bind:open={open}>
    <Popover.Trigger title="Filter log severity." class="h-full flex items-center justify-center">
        <div class="h-full flex items-center justify-center gap-2 bg-surface-content-button rounded-md px-4">
            <p>Log Type</p>
        </div>
    </Popover.Trigger>
    <Popover.Content preventScroll={true} align="end" class="relative z-50">
        <div class="w-[14rem] surface-popover-container">
            <button on:click={selectAll} class="surface-popover-button font-semibold mb-2">
                <span>Select all</span>
            </button>

            {#each types as type}
                <button on:click={() => toggleType(type)} class="surface-popover-button">
                    <div class="size-5 flex-shrink-0">
                        {#if selectedTypes.includes(type)}
                            <CheckmarkIcon />
                        {/if}
                    </div>
                    <span>{type}</span>
                </button>
            {/each}
        </div>
    </Popover.Content>
</Popover.Root>