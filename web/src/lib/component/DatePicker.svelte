<script lang="ts">
    import { DatePicker, type DatePickerRootPropsWithoutHTML } from "bits-ui";
    import ChevronLeftIcon from "./icons/ChevronLeftIcon.svelte";
    import ChevronRightIcon from "./icons/ChevronRightIcon.svelte";
    import CalendarIcon from "./icons/CalendarIcon.svelte";

    let { value = $bindable() }: { value: DatePickerRootPropsWithoutHTML["value"] } = $props()
</script>
 
<DatePicker.Root bind:value={value} weekdayFormat="short" fixedWeeks={true} locale="en-GB">
    <div class="flex w-full max-w-[232px] flex-col gap-1.5">
        <DatePicker.Input
            class="h-input rounded-lg border border-neutral-200 dark:border-neutral-800 bg-bg text-foreground focus-within:border-neutral-300 dark:focus-within:border-neutral-700 focus-within:shadow-date-field-focus hover:border-neutral-300 dark:hover:border-neutral-700 flex w-full max-w-[232px] select-none items-center px-2 py-3 text-sm tracking-[0.01em]"
        >
            {#snippet children({ segments })}
                {#each segments as { part, value }, i (part + i)}
                    <div class="inline-block select-none">
                        {#if part === "literal"}
                            <DatePicker.Segment {part} class="text-muted-foreground p-1">
                                {value}
                            </DatePicker.Segment>
                        {:else}
                            <DatePicker.Segment
                                {part}
                                class="rounded-lg hover:bg-muted focus:bg-surface-button focus:text-foreground aria-[valuetext=Empty]:text-muted-foreground focus-visible:outline-none px-1 py-1"
                            >
                                {value}
                            </DatePicker.Segment>
                        {/if}
                    </div>
                {/each}
                <DatePicker.Trigger
                    class="text-foreground/60 bg-surface-button ml-auto inline-flex size-8 items-center justify-center rounded-lg active:scale-95"
                >
                    <div class="size-6">
                        <CalendarIcon></CalendarIcon>
                    </div>
                </DatePicker.Trigger>
            {/snippet}
        </DatePicker.Input>
        <DatePicker.Content sideOffset={6} class="z-50">
            <DatePicker.Calendar
                class="border border-neutral-200 dark:border-neutral-800 bg-surface shadow-popover rounded-lg p-[22px]"
            >
                {#snippet children({ months, weekdays })}
                    <DatePicker.Header class="flex items-center justify-between">
                        <DatePicker.PrevButton
                            class="rounded-lg bg-surface-button inline-flex size-10 items-center justify-center transition-none active:scale-[0.98]"
                        >
                            <div class="size-6">
                                <ChevronLeftIcon />
                            </div>
                        </DatePicker.PrevButton>
                        <DatePicker.Heading class="text-[15px] font-medium" />
                        <DatePicker.NextButton
                            class="rounded-lg bg-surface-button inline-flex size-10 items-center justify-center transition-none active:scale-[0.98]"
                        >
                            <div class="size-6">
                                <ChevronRightIcon />
                            </div>
                        </DatePicker.NextButton>
                    </DatePicker.Header>
                    <div
                        class="flex flex-col space-y-4 pt-4 sm:flex-row sm:space-x-4 sm:space-y-0"
                    >
                        {#each months as month (month.value)}
                            <DatePicker.Grid
                                class="w-full border-collapse select-none space-y-1"
                            >
                                <DatePicker.GridHead>
                                    <DatePicker.GridRow class="mb-1 flex w-full justify-between">
                                        {#each weekdays as day (day)}
                                        <DatePicker.HeadCell
                                            class="text-muted-foreground font-normal! w-10 rounded-md text-xs"
                                        >
                                            <div>{day.slice(0, 2)}</div>
                                        </DatePicker.HeadCell>
                                        {/each}
                                    </DatePicker.GridRow>
                                </DatePicker.GridHead>
                                <DatePicker.GridBody>
                                    {#each month.weeks as weekDates (weekDates)}
                                        <DatePicker.GridRow class="flex w-full">
                                        {#each weekDates as date (date)}
                                            <DatePicker.Cell
                                                {date}
                                                month={month.value}
                                                class="p-0! relative size-10 text-center text-sm"
                                            >
                                                <DatePicker.Day
                                                    class="rounded-lg text-foreground hover:bg-surface-button data-selected:bg-surface-button data-selected:border-2 data-selected:border-foreground data-selected:text-foreground data-selected:font-medium data-disabled:text-foreground/30 data-unavailable:text-muted-foreground data-disabled:pointer-events-none data-unavailable:line-through data-outside-month:opacity-40 data-outside-month:text-muted-foreground group relative inline-flex size-10 items-center justify-center whitespace-nowrap border border-neutral-100 dark:border-neutral-900 bg-transparent p-0 text-sm font-normal transition-none"
                                                >
                                                    <div
                                                        class="bg-foreground group-data-selected:bg-transparent group-data-today:block absolute top-[5px] hidden size-1 rounded-full transition-none"
                                                    ></div>
                                                    {date.day}
                                                </DatePicker.Day>
                                            </DatePicker.Cell>
                                        {/each}
                                    </DatePicker.GridRow>
                                {/each}
                                </DatePicker.GridBody>
                            </DatePicker.Grid>
                        {/each}
                    </div>
                {/snippet}
            </DatePicker.Calendar>
        </DatePicker.Content>
    </div>
</DatePicker.Root>