<script lang="ts">
    import { uiState } from "$lib/code/stateObjects/uiState.svelte"
    import CustomSidebar from "$lib/component/CustomSidebar.svelte"
    import DatePicker from "$lib/component/DatePicker.svelte"
    import type { DatePickerRootPropsWithoutHTML } from "bits-ui"
    import { onMount } from "svelte"
    import SeveritySelectionButton from "./_components/SeveritySelectionButton.svelte"
    import type { Log, LogLevel, LogType } from "./_components/logs"
    import LogTypeSelectionButton from "./_components/LogTypeSelectionButton.svelte"
    import { explicitEffect, formData, handleErr, handleException, safeFetch } from "$lib/code/util/codeUtil.svelte"
    import ChevronLeftIcon from "$lib/component/icons/ChevronLeftIcon.svelte"
    import ChevronRightIcon from "$lib/component/icons/ChevronRightIcon.svelte"
    import LogDetailOverlay from "./_components/LogDetailOverlay.svelte"

    const title = "Logs"
    onMount(() => {
        uiState.settings.title = title
    })

    // Data
    let page = $state(0)
    let amount = $state(50)
    let logs: Log[] | null = $state(null)
    let currentLog: Log | null = $state(null)
    let loading = $state(false)
    let isLastPage = $derived.by(() => { return logs && logs.length < amount })

    // Selections
    let logTypes: LogType[] = $state([])
    let logTypePopoverOpen = $state(false)

    let severities: LogLevel[] = $state([])
    let severitiesPopoverOpen = $state(false)

    // Inputs
    let ipInput = $state("")
    let initiatorIdInput = $state("")
    let targetIdInput = $state("")
    let searchInput = $state("")

    // Dates
    let fromDate = $state() as DatePickerRootPropsWithoutHTML["value"]
    let toDate = $state()   as DatePickerRootPropsWithoutHTML["value"]

    let isSidebarOpen = $state(false)
    let isLogOpen = $state(false)

    onMount(() => {
        loadData()
    })

    explicitEffect(() => [ logs ], () => { currentLog = null })

    async function loadData() {
        if (loading) return
        loading = true

        try {
            logs = null

            const body = formData({
                "page": page,
                "amount": amount,
                "severity-list": JSON.stringify(severities),
                "log-type-list": JSON.stringify(logTypes),
            })
            if (initiatorIdInput) body.append("initiator-id", initiatorIdInput)
            if (targetIdInput) body.append("target-id", targetIdInput)
            if (ipInput) body.append("ip", ipInput)
            if (fromDate) body.append("from-date", (fromDate.toDate("UTC").getTime() / 1000).toString())
            if (toDate) body.append("to-date", (toDate.toDate("UTC").getTime() / 1000).toString())
            if (searchInput) body.append("search-text", searchInput)

            const response = await safeFetch(`/api/v1/admin/log/get`, { body: body })
            if (response.failed) {
                handleException(`Failed to fetch logs`, `Failed to load logs.`, response.exception)
                return
            }

            const json = response.json()
            if (response.code.failed) {
                handleErr({
                    description: `Failed to fetch logs`,
                    notification: json.message || `Failed to load logs.`,
                    isServerDown: response.code.serverDown
                })
                return
            }
            logs = json
        } finally {
            loading = false
        }
    }

    function increasePage() {
        if (loading) return
        page++
        loadData()
    }
    function decreasePage() {
        if (loading) return
        if (page === 0) return
        page--
        loadData()
    }

    function openLog(log: Log) {
        currentLog = log
        isLogOpen = true
    }
</script>

<div class="page flex">
    <div class="page content-width flex-col">
        <!-- Tailwind decided it will ignore this page, so some classes will be defined in app.css -->
        <!-- i dont care -->
        <div class="settings-margin mb-0! flex gap-4 items-center justify-between h-14">
            <button class="lg:hidden basic-button" on:click={() => { isSidebarOpen = true }}>Filter</button>
            <!-- Page navigator -->
            <div class="flex items-center gap-3 h-full">
                <button
                    class="basic-button size-10! p-3 disabled:opacity-50"
                    aria-label="Previous page"
                    on:click={decreasePage}
                    disabled={page === 0}
                >
                    <ChevronLeftIcon class="m-auto" />
                </button>

                <input
                    name="Page"
                    type="number"
                    class="basic-input text-center !mixn-w-fit !w-[5rem]"
                    bind:value={page}
                    min="0"
                >

                <button
                    class="basic-button size-10! p-3 disabled:opacity-50"
                    aria-label="Next page"
                    on:click={increasePage}
                    disabled={isLastPage}
                >
                    <ChevronRightIcon class="m-auto" />
                </button>
            </div>
        </div>

        {#if logs}
            <div class="flex flex-col h-full overflow-y-auto custom-scrollbar settings-margin gap-2">
                {#each logs as log}
                    <button on:click={() => { openLog(log) }} class="rounded-md bg-surface/50 p-3 flex items-center gap-3">
                        <span
                            class="px-2 py-1 rounded text-xs font-medium whitespace-nowrap shrink-0"
                            class:bg-blue-500={log.level === "DEBUG"}
                            class:bg-green-500={log.level === "INFO"}
                            class:bg-yellow-500={log.level === "WARN"}
                            class:bg-red-500={log.level === "ERROR"}
                            class:bg-purple-500={log.level === "FATAL"}
                        >
                            {log.level}
                        </span>
                        <p class="text-sm text-neutral-300 flex-1 truncate text-start">{log.description}</p>
                        <time class="text-xs text-neutral-400 whitespace-nowrap shrink-0">
                            {new Date(log.createdDate * 1000).toLocaleString('en-GB', { hour12: false })}
                        </time>
                    </button>
                {/each}
            </div>
        {/if}
    </div>
    
    <div class="sidebar-width h-full">
        <CustomSidebar
            bind:open={isSidebarOpen}
            side={"right"}
        >
            <div class="flex flex-col gap-4">
                <div class="w-full flex-col">
                    <p>Search text</p>
                    <input bind:value={searchInput} class="basic-input">
                </div>
                
                <div class="w-full flex-col" title="ID of action initiator">
                    <p>User ID</p>
                    <input bind:value={initiatorIdInput} class="basic-input">
                </div>

                <div class="w-full flex-col" title="ID of action target">
                    <p>Target ID</p>
                    <input bind:value={targetIdInput} class="basic-input">
                </div>

                <div class="w-full flex-col">
                    <p>IP address</p>
                    <input bind:value={ipInput} class="basic-input" placeholder="1.2.3.4">
                </div>

                <div class="w-full flex-col">
                    <SeveritySelectionButton bind:open={severitiesPopoverOpen} bind:selectedSeverities={severities} />
                </div>
                <div class="w-full flex-col">
                    <LogTypeSelectionButton bind:open={logTypePopoverOpen} bind:selectedTypes={logTypes} />
                </div>

                <div class="w-full flex-col">
                    <p>From</p>
                    <DatePicker bind:value={fromDate}></DatePicker>
                    <button on:click={() => { fromDate = undefined }} class="text-sm basic-button !bg-surface-content-button">Reset</button>
                </div>

                <div class="w-full flex-col">
                    <p>To</p>
                    <DatePicker bind:value={toDate}></DatePicker>
                    <button on:click={() => { toDate = undefined }} class="text-sm basic-button !bg-surface-content-button">Reset</button>
                </div>

                <div class="w-full flex-col">
                    <button disabled={loading} on:click={() => { loadData() }} class="basic-button !bg-surface-content-button rounded-md w-full text-center">Refresh</button>
                </div>
            </div>
        </CustomSidebar>
    </div>

    {#if currentLog}
        <LogDetailOverlay log={currentLog} onClose={() => { currentLog = null }} />
    {/if}
</div>

<style lang="postcss">
    :root {
        --filter-sidebar-width: 14rem;
    }
    .sidebar-width {
        @apply w-0 lg:w-(--filter-sidebar-width);
    }
    .content-width {
        @apply w-full lg:w-[calc(100%-var(--filter-sidebar-width))]!;
    }
</style>