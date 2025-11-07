<script lang="ts">
    import { Popover } from "$lib/component/bits-ui-wrapper";
    import CheckmarkIcon from "$lib/component/icons/CheckmarkIcon.svelte";
    import type { LogLevel } from "./logs";

    const severities: LogLevel[] = ["DEBUG", "INFO", "WARN", "ERROR", "FATAL"];

    let {
        selectedSeverities = $bindable(),
        open = $bindable(),
    }: {
        selectedSeverities: LogLevel[],
        open: boolean
    } = $props()

    function toggleSeverity(severity: LogLevel) {
        if (selectedSeverities.includes(severity)) {
            selectedSeverities = selectedSeverities.filter(s => s !== severity);
        } else {
            selectedSeverities = [...selectedSeverities, severity];
        }
    }

    function selectAll() {
        selectedSeverities = [...severities];
    }
</script>

<Popover.Root bind:open={open}>
    <Popover.Trigger title="Filter log severity." class="h-full flex items-center justify-center">
        <div class="h-full flex items-center justify-center gap-2 bg-surface-button rounded-md px-4">
            <p>Severity</p>
        </div>
    </Popover.Trigger>
    <Popover.Content align="end" class="relative z-50">
        <div class="w-[14rem] surface-popover-container">
            <button on:click={selectAll} class="surface-popover-button font-semibold mb-2">
                <span>Select all</span>
            </button>

            {#each severities as severity}
                <button on:click={() => toggleSeverity(severity)} class="surface-popover-button">
                    <div class="size-5 flex-shrink-0">
                        {#if selectedSeverities.includes(severity)}
                            <CheckmarkIcon />
                        {/if}
                    </div>
                    <span>{severity}</span>
                </button>
            {/each}
        </div>
    </Popover.Content>
</Popover.Root>